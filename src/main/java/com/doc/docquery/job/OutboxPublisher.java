package com.doc.docquery.job;

import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.entity.OutboxEventEntity;
import com.doc.docquery.enums.OutboxEventStatus;
import com.doc.docquery.enums.OutboxEventType;
import com.doc.docquery.mapper.OutboxEventMapper;
import com.doc.docquery.messaging.ConfirmedRabbitPublisher;
import com.doc.docquery.messaging.DocumentProcessingMessage;
import com.doc.docquery.messaging.DocumentDeletionMessage;
import com.doc.docquery.messaging.DocumentDeletionRabbitMqTopology;
import com.doc.docquery.messaging.RabbitMqTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Transactional Outbox 到 RabbitMQ 的租约式 Publisher。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "publisher-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String PENDING = OutboxEventStatus.PENDING.getCode();
    private static final String PUBLISHING = OutboxEventStatus.PUBLISHING.getCode();
    private static final String SENT = OutboxEventStatus.SENT.getCode();
    private static final String PROCESS_REQUESTED =
            OutboxEventType.DOCUMENT_VERSION_PROCESS_REQUESTED.getCode();
    private static final String DELETE_REQUESTED =
            OutboxEventType.DOCUMENT_DELETE_REQUESTED.getCode();

    private final OutboxEventMapper outboxEventMapper;
    private final ConfirmedRabbitPublisher rabbitPublisher;
    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final String publisherInstanceId = "publisher-" + UUID.randomUUID();

    public OutboxPublisher(
            OutboxEventMapper outboxEventMapper,
            ConfirmedRabbitPublisher rabbitPublisher,
            MessagingProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitPublisher = rabbitPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 定时入口；Broker 不可用只推进下次可发布时间，不影响 HTTP 上传结果。 */
    @Scheduled(fixedDelayString = "${docquery.messaging.publisher-delay:1s}")
    public void publishAvailable() {
        for (OutboxEventEntity event : claimBatch()) {
            publishClaimed(event);
        }
    }

    /** 在短事务内回收过期租约并领取有限批次。 */
    public List<OutboxEventEntity> claimBatch() {
        List<OutboxEventEntity> claimed = transaction.execute(status -> {
            LocalDateTime now = nowUtc();
            outboxEventMapper.recoverExpiredLeases(PENDING, PUBLISHING, now);
            List<OutboxEventEntity> candidates = outboxEventMapper
                    .findPublishableForUpdate(
                            PENDING,
                            now,
                            properties.getPublisherBatchSize()
                    );
            List<OutboxEventEntity> owned = new ArrayList<>();
            for (OutboxEventEntity event : candidates) {
                if (outboxEventMapper.claim(
                        event.getId(),
                        PENDING,
                        PUBLISHING,
                        publisherInstanceId,
                        now.plus(properties.getPublisherLease()),
                        now
                ) == 1) {
                    event.setStatus(PUBLISHING);
                    event.setAttemptCount(event.getAttemptCount() + 1);
                    event.setLockedBy(publisherInstanceId);
                    event.setLockedUntil(now.plus(properties.getPublisherLease()));
                    owned.add(event);
                }
            }
            return owned;
        });
        return claimed == null ? List.of() : claimed;
    }

    private void publishClaimed(OutboxEventEntity event) {
        boolean confirmed;
        try {
            confirmed = publishEvent(event);
        } catch (RuntimeException exception) {
            confirmed = false;
        }

        LocalDateTime now = nowUtc();
        if (confirmed) {
            outboxEventMapper.markSent(
                    event.getId(),
                    publisherInstanceId,
                    PUBLISHING,
                    SENT,
                    now
            );
            return;
        }

        LocalDateTime availableAt = now.plus(backoff(event.getAttemptCount()));
        outboxEventMapper.markPublishFailed(
                event.getId(),
                publisherInstanceId,
                PUBLISHING,
                PENDING,
                availableAt,
                "RABBITMQ_PUBLISH_FAILED",
                "Broker did not confirm the message",
                now
        );
        LOG.warn("Outbox event publish was not confirmed; event remains pending");
    }

    private boolean publishEvent(OutboxEventEntity event) {
        PayloadIds payload = objectMapper.readValue(event.getPayload(), PayloadIds.class);
        if (PROCESS_REQUESTED.equals(event.getEventType())) {
            DocumentProcessingMessage message = new DocumentProcessingMessage(
                    1, event.getId(), event.getEventType(),
                    required(payload.tenantId), required(payload.knowledgeBaseId),
                    required(payload.documentId), required(payload.documentVersionId),
                    required(payload.processingJobId)
            );
            return rabbitPublisher.publish(
                    RabbitMqTopology.MAIN_EXCHANGE,
                    RabbitMqTopology.MAIN_ROUTING_KEY,
                    objectMapper.writeValueAsBytes(message),
                    Long.toString(event.getId()),
                    Map.of(RabbitMqTopology.RETRY_HEADER, 0)
            );
        }
        if (DELETE_REQUESTED.equals(event.getEventType())) {
            DocumentDeletionMessage message = new DocumentDeletionMessage(
                    1, event.getId(), event.getEventType(),
                    required(payload.tenantId), required(payload.knowledgeBaseId),
                    required(payload.documentId), required(payload.documentDeletionJobId)
            );
            return rabbitPublisher.publish(
                    DocumentDeletionRabbitMqTopology.MAIN_EXCHANGE,
                    DocumentDeletionRabbitMqTopology.MAIN_ROUTING_KEY,
                    objectMapper.writeValueAsBytes(message),
                    Long.toString(event.getId()),
                    Map.of(DocumentDeletionRabbitMqTopology.RETRY_HEADER, 0)
            );
        }
        throw new IllegalArgumentException("Unsupported Outbox event type");
    }

    private long required(Long value) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException("Outbox payload ID is missing");
        }
        return value;
    }

    private Duration backoff(int attemptCount) {
        long seconds = switch (Math.max(attemptCount, 1)) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 30;
            case 4 -> 120;
            default -> 600;
        };
        long jitterMillis = ThreadLocalRandom.current().nextLong(
                Math.max(1, seconds * 200L + 1)
        );
        return Duration.ofSeconds(seconds).plusMillis(jitterMillis);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 反序列化 N2.1 内部 Outbox Payload，不接收外部请求。 */
    private static final class PayloadIds {
        public Long tenantId;
        public Long knowledgeBaseId;
        public Long documentId;
        public Long documentVersionId;
        public Long processingJobId;
        public Long documentDeletionJobId;
    }
}
