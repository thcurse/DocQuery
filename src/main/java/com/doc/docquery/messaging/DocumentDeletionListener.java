package com.doc.docquery.messaging;

import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.entity.DocumentDeletionJobEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.enums.DocumentDeletionJobStatus;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.enums.OutboxEventType;
import com.doc.docquery.mapper.DocumentDeletionJobMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.service.DocumentDeletionException;
import com.doc.docquery.service.DocumentDeletionProcessor;
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

/** N4.2 手动 ACK 的至少一次文档删除 Consumer。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "deletion-listener-enabled",
        havingValue = "true"
)
public class DocumentDeletionListener {

    private static final String PENDING = DocumentDeletionJobStatus.PENDING.getCode();
    private static final String RUNNING = DocumentDeletionJobStatus.RUNNING.getCode();
    private static final String SUCCEEDED = DocumentDeletionJobStatus.SUCCEEDED.getCode();
    private static final String FAILED = DocumentDeletionJobStatus.FAILED.getCode();
    private static final String DELETING = DocumentStatus.DELETING.getCode();
    private static final String DELETED = DocumentStatus.DELETED.getCode();
    private static final String EVENT_TYPE = OutboxEventType.DOCUMENT_DELETE_REQUESTED.getCode();

    private final ObjectMapper objectMapper;
    private final DocumentDeletionJobMapper jobMapper;
    private final DocumentMapper documentMapper;
    private final DocumentDeletionProcessor processor;
    private final ConfirmedRabbitPublisher rabbitPublisher;
    private final DocumentDeletionLeaseHeartbeat heartbeat;
    private final MessagingProperties properties;
    private final TransactionTemplate transaction;
    private final String consumerInstanceId = "deletion-consumer-" + UUID.randomUUID();

    public DocumentDeletionListener(
            ObjectMapper objectMapper,
            DocumentDeletionJobMapper jobMapper,
            DocumentMapper documentMapper,
            DocumentDeletionProcessor processor,
            ConfirmedRabbitPublisher rabbitPublisher,
            DocumentDeletionLeaseHeartbeat heartbeat,
            MessagingProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.objectMapper = objectMapper;
        this.jobMapper = jobMapper;
        this.documentMapper = documentMapper;
        this.processor = processor;
        this.rabbitPublisher = rabbitPublisher;
        this.heartbeat = heartbeat;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @RabbitListener(
            queues = DocumentDeletionRabbitMqTopology.MAIN_QUEUE,
            concurrency = "${docquery.messaging.consumer-concurrency:1}",
            ackMode = "MANUAL"
    )
    public void onMessage(Message raw, Channel channel) throws IOException {
        long deliveryTag = raw.getMessageProperties().getDeliveryTag();
        DocumentDeletionMessage message;
        try {
            message = objectMapper.readValue(raw.getBody(), DocumentDeletionMessage.class);
            validateMessage(message);
        } catch (RuntimeException exception) {
            publishDlqOrRequeue(raw, channel, deliveryTag, "invalid-deletion");
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
        if (jobMapper.acquireLease(
                facts.job().getId(), facts.job().getTenantId(), facts.job().getDocumentId(),
                consumerInstanceId, now.plus(properties.getJobLease()), now,
                PENDING, RUNNING
        ) != 1) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        facts.job().setStatus(RUNNING);
        facts.job().setLeaseOwner(consumerInstanceId);

        int retryCount = retryCount(raw);
        try (DocumentDeletionLeaseHeartbeat.Handle ignored = heartbeat.start(
                facts.job().getId(), consumerInstanceId
        )) {
            processor.process(new DocumentDeletionProcessor.Context(
                    message, facts.document(), facts.job(), consumerInstanceId
            ));
            channel.basicAck(deliveryTag, false);
        } catch (DocumentDeletionException exception) {
            handleFailure(
                    raw, channel, deliveryTag, facts.job().getId(), retryCount,
                    exception.code(), "Document deletion failed", exception.retryable()
            );
        } catch (RuntimeException exception) {
            handleFailure(
                    raw, channel, deliveryTag, facts.job().getId(), retryCount,
                    "DOCUMENT_DELETION_DEPENDENCY_UNAVAILABLE",
                    "Document deletion dependency is unavailable",
                    true
            );
        }
    }

    private void handleFailure(
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
            jobMapper.releaseForRetry(
                    jobId, consumerInstanceId, nowUtc(), PENDING, RUNNING
            );
            int nextRetry = retryCount + 1;
            boolean confirmed = rabbitPublisher.publish(
                    DocumentDeletionRabbitMqTopology.RETRY_EXCHANGE,
                    retryRouting(nextRetry),
                    raw.getBody(),
                    safeMessageId(raw, "deletion-retry-" + nextRetry),
                    Map.of(DocumentDeletionRabbitMqTopology.RETRY_HEADER, nextRetry)
            );
            if (confirmed) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
            return;
        }
        transaction.executeWithoutResult(status -> jobMapper.markFailed(
                jobId, consumerInstanceId, failureCode, failureMessage, retryable,
                nowUtc(), PENDING, RUNNING, FAILED
        ));
        publishDlqOrRequeue(raw, channel, deliveryTag, safeMessageId(raw, "deletion-failed"));
    }

    private TrustedFacts loadAndValidateFacts(DocumentDeletionMessage message) {
        DocumentDeletionJobEntity job = jobMapper.findById(message.documentDeletionJobId());
        DocumentEntity document = documentMapper.findByTenantAndId(
                message.tenantId(), message.documentId()
        );
        if (job == null || document == null
                || !EVENT_TYPE.equals(message.eventType())
                || !job.getTenantId().equals(message.tenantId())
                || !job.getKnowledgeBaseId().equals(message.knowledgeBaseId())
                || !job.getDocumentId().equals(message.documentId())
                || !document.getKnowledgeBaseId().equals(message.knowledgeBaseId())
                || (!DELETING.equals(document.getStatus())
                    && !(SUCCEEDED.equals(job.getStatus())
                        && DELETED.equals(document.getStatus())))) {
            throw new IllegalArgumentException("Deletion message facts do not match");
        }
        return new TrustedFacts(document, job);
    }

    private void validateMessage(DocumentDeletionMessage message) {
        if (message == null || message.schemaVersion() != 1 || message.eventId() < 1
                || message.tenantId() < 1 || message.knowledgeBaseId() < 1
                || message.documentId() < 1 || message.documentDeletionJobId() < 1) {
            throw new IllegalArgumentException("Deletion message is invalid");
        }
    }

    private void publishDlqOrRequeue(
            Message raw,
            Channel channel,
            long deliveryTag,
            String fallbackId
    ) throws IOException {
        boolean confirmed = rabbitPublisher.publish(
                DocumentDeletionRabbitMqTopology.DLX_EXCHANGE,
                DocumentDeletionRabbitMqTopology.DLQ_ROUTING_KEY,
                raw.getBody(),
                safeMessageId(raw, fallbackId),
                Map.of(DocumentDeletionRabbitMqTopology.RETRY_HEADER, retryCount(raw))
        );
        if (confirmed) {
            channel.basicAck(deliveryTag, false);
        } else {
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private int retryCount(Message raw) {
        Object value = raw.getMessageProperties().getHeaders().get(
                DocumentDeletionRabbitMqTopology.RETRY_HEADER
        );
        if (value instanceof Number number) {
            return Math.max(0, Math.min(3, number.intValue()));
        }
        return 0;
    }

    private String retryRouting(int retry) {
        return switch (retry) {
            case 1 -> DocumentDeletionRabbitMqTopology.RETRY_ROUTING_1;
            case 2 -> DocumentDeletionRabbitMqTopology.RETRY_ROUTING_2;
            default -> DocumentDeletionRabbitMqTopology.RETRY_ROUTING_3;
        };
    }

    private String safeMessageId(Message raw, String fallback) {
        String messageId = raw.getMessageProperties().getMessageId();
        return messageId == null || messageId.isBlank() ? fallback : messageId;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record TrustedFacts(DocumentEntity document, DocumentDeletionJobEntity job) {
    }
}
