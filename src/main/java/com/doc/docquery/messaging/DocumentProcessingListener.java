package com.doc.docquery.messaging;

import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.enums.OutboxEventType;
import com.doc.docquery.enums.ProcessingJobStatus;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.service.DocumentIngestionProcessor;
import com.doc.docquery.service.DocumentProcessingException;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * 手动 ACK 的至少一次文档处理 Consumer 骨架。
 *
 * <p>消息中的关联 ID 必须与 MySQL 事实完全一致；生产环境只有在后续完整
 * Processor 存在且显式启用后才会创建本 Listener。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "listener-enabled",
        havingValue = "true"
)
public class DocumentProcessingListener {

    private static final String PENDING = ProcessingJobStatus.PENDING.getCode();
    private static final String RUNNING = ProcessingJobStatus.RUNNING.getCode();
    private static final String SUCCEEDED = ProcessingJobStatus.SUCCEEDED.getCode();
    private static final String FAILED = ProcessingJobStatus.FAILED.getCode();
    private static final String VERSION_PROCESSING =
            DocumentVersionStatus.PROCESSING.getCode();
    private static final String VERSION_FAILED = DocumentVersionStatus.FAILED.getCode();
    private static final String EVENT_TYPE =
            OutboxEventType.DOCUMENT_VERSION_PROCESS_REQUESTED.getCode();

    private final ObjectMapper objectMapper;
    private final ProcessingJobMapper processingJobMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentMapper documentMapper;
    private final DocumentIngestionProcessor processor;
    private final ConfirmedRabbitPublisher rabbitPublisher;
    private final ProcessingLeaseHeartbeat heartbeat;
    private final MessagingProperties properties;
    private final TransactionTemplate transaction;
    private final String consumerInstanceId = "consumer-" + UUID.randomUUID();

    public DocumentProcessingListener(
            ObjectMapper objectMapper,
            ProcessingJobMapper processingJobMapper,
            DocumentVersionMapper documentVersionMapper,
            DocumentMapper documentMapper,
            DocumentIngestionProcessor processor,
            ConfirmedRabbitPublisher rabbitPublisher,
            ProcessingLeaseHeartbeat heartbeat,
            MessagingProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.objectMapper = objectMapper;
        this.processingJobMapper = processingJobMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentMapper = documentMapper;
        this.processor = processor;
        this.rabbitPublisher = rabbitPublisher;
        this.heartbeat = heartbeat;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Rabbit Listener 只在配置显式开启时注册。 */
    @RabbitListener(
            queues = RabbitMqTopology.MAIN_QUEUE,
            concurrency = "${docquery.messaging.consumer-concurrency:1}",
            ackMode = "MANUAL"
    )
    public void onMessage(Message raw, Channel channel) throws IOException {
        long deliveryTag = raw.getMessageProperties().getDeliveryTag();
        DocumentProcessingMessage message;
        try {
            message = objectMapper.readValue(raw.getBody(), DocumentProcessingMessage.class);
            validateMessage(message);
        } catch (RuntimeException exception) {
            publishDlqOrRequeue(raw, channel, deliveryTag, "invalid");
            return;
        }

        TrustedFacts facts;
        try {
            facts = loadAndValidateFacts(message);
        } catch (RuntimeException exception) {
            publishDlqOrRequeue(raw, channel, deliveryTag, Long.toString(message.eventId()));
            return;
        }

        if (SUCCEEDED.equals(facts.job().getStatus())) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (FAILED.equals(facts.job().getStatus())) {
            publishDlqOrRequeue(raw, channel, deliveryTag, Long.toString(message.eventId()));
            return;
        }

        LocalDateTime now = nowUtc();
        int acquired = processingJobMapper.acquireLease(
                facts.job().getId(),
                facts.job().getTenantId(),
                facts.job().getDocumentVersionId(),
                consumerInstanceId,
                now.plus(properties.getJobLease()),
                now,
                PENDING,
                RUNNING
        );
        if (acquired != 1) {
            // 未过期租约代表另一个投递正在处理；ACK 当前重复副本避免并发副作用。
            channel.basicAck(deliveryTag, false);
            return;
        }

        int retryCount = retryCount(raw);
        try (ProcessingLeaseHeartbeat.Handle ignored = heartbeat.start(
                facts.job().getId(),
                consumerInstanceId
        )) {
            processor.process(new DocumentIngestionProcessor.Context(
                    message,
                    facts.document(),
                    facts.version(),
                    facts.job(),
                    consumerInstanceId
            ));
            channel.basicAck(deliveryTag, false);
        } catch (DocumentProcessingException exception) {
            handleProcessingFailure(
                    raw,
                    channel,
                    deliveryTag,
                    facts.job().getId(),
                    retryCount,
                    exception.code(),
                    safeFailureMessage(),
                    exception.retryable()
            );
        } catch (RuntimeException exception) {
            handleProcessingFailure(
                    raw,
                    channel,
                    deliveryTag,
                    facts.job().getId(),
                    retryCount,
                    "PROCESSING_DEPENDENCY_UNAVAILABLE",
                    "Document processing dependency is unavailable",
                    true
            );
        }
    }

    private void handleProcessingFailure(
            Message raw,
            Channel channel,
            long deliveryTag,
            long jobId,
            int retryCount,
            String failureCode,
            String failureMessage,
            boolean retryable
    ) throws IOException {
        if (retryable && retryCount < 3) {
            processingJobMapper.releaseForRetry(
                    jobId,
                    consumerInstanceId,
                    nowUtc(),
                    PENDING,
                    RUNNING
            );
            int nextRetry = retryCount + 1;
            boolean confirmed = rabbitPublisher.publish(
                    RabbitMqTopology.RETRY_EXCHANGE,
                    retryRouting(nextRetry),
                    raw.getBody(),
                    safeMessageId(raw, "retry-" + nextRetry),
                    Map.of(RabbitMqTopology.RETRY_HEADER, nextRetry)
            );
            if (confirmed) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
            return;
        }

        markFailed(jobId, failureCode, failureMessage, retryable);
        publishDlqOrRequeue(raw, channel, deliveryTag, safeMessageId(raw, "failed"));
    }

    private TrustedFacts loadAndValidateFacts(DocumentProcessingMessage message) {
        ProcessingJobEntity job = processingJobMapper.findById(message.processingJobId());
        DocumentVersionEntity version = documentVersionMapper.findById(
                message.documentVersionId()
        );
        DocumentEntity document = documentMapper.findByTenantAndId(
                message.tenantId(),
                message.documentId()
        );
        if (job == null
                || version == null
                || document == null
                || !EVENT_TYPE.equals(message.eventType())
                || !job.getTenantId().equals(message.tenantId())
                || !job.getDocumentVersionId().equals(message.documentVersionId())
                || !version.getTenantId().equals(message.tenantId())
                || !version.getDocumentId().equals(message.documentId())
                || !document.getKnowledgeBaseId().equals(message.knowledgeBaseId())) {
            throw new IllegalArgumentException("Message facts do not match");
        }
        return new TrustedFacts(document, version, job);
    }

    private void validateMessage(DocumentProcessingMessage message) {
        if (message == null
                || message.schemaVersion() != 1
                || message.eventId() < 1
                || message.tenantId() < 1
                || message.knowledgeBaseId() < 1
                || message.documentId() < 1
                || message.documentVersionId() < 1
                || message.processingJobId() < 1) {
            throw new IllegalArgumentException("Message is invalid");
        }
    }

    private void markFailed(
            long jobId,
            String code,
            String failureMessage,
            boolean retryable
    ) {
        transaction.executeWithoutResult(status -> {
            ProcessingJobEntity job = processingJobMapper.findById(jobId);
            if (job == null) {
                return;
            }
            LocalDateTime now = nowUtc();
            processingJobMapper.markFailed(
                    jobId,
                    consumerInstanceId,
                    code,
                    failureMessage,
                    retryable,
                    now,
                    PENDING,
                    RUNNING,
                    FAILED
            );
            documentVersionMapper.markFailed(
                    job.getDocumentVersionId(),
                    code,
                    failureMessage,
                    now,
                    VERSION_PROCESSING,
                    VERSION_FAILED
            );
        });
    }

    private void publishDlqOrRequeue(
            Message raw,
            Channel channel,
            long deliveryTag,
            String fallbackId
    ) throws IOException {
        boolean confirmed = rabbitPublisher.publish(
                RabbitMqTopology.DLX_EXCHANGE,
                RabbitMqTopology.DLQ_ROUTING_KEY,
                raw.getBody(),
                safeMessageId(raw, fallbackId),
                Map.of(RabbitMqTopology.RETRY_HEADER, retryCount(raw))
        );
        if (confirmed) {
            channel.basicAck(deliveryTag, false);
        } else {
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private int retryCount(Message raw) {
        Object value = raw.getMessageProperties().getHeaders().get(
                RabbitMqTopology.RETRY_HEADER
        );
        if (value instanceof Number number) {
            return Math.max(0, Math.min(3, number.intValue()));
        }
        return 0;
    }

    private String retryRouting(int retry) {
        return switch (retry) {
            case 1 -> RabbitMqTopology.RETRY_ROUTING_1;
            case 2 -> RabbitMqTopology.RETRY_ROUTING_2;
            default -> RabbitMqTopology.RETRY_ROUTING_3;
        };
    }

    private String safeMessageId(Message raw, String fallback) {
        String messageId = raw.getMessageProperties().getMessageId();
        return messageId == null || messageId.isBlank() ? fallback : messageId;
    }

    private String safeFailureMessage() {
        // Processor 异常可能含文件名、正文片段、对象地址或下游响应，不能原样落库。
        return "Document processing failed";
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record TrustedFacts(
            DocumentEntity document,
            DocumentVersionEntity version,
            ProcessingJobEntity job
    ) {
    }
}
