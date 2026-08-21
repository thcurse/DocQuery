package com.doc.docquery.messaging;

import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.enums.DocumentDeletionJobStatus;
import com.doc.docquery.mapper.DocumentDeletionJobMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** 删除外部内容期间按 owner 条件续租。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "deletion-listener-enabled",
        havingValue = "true"
)
public class DocumentDeletionLeaseHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentDeletionLeaseHeartbeat.class);
    private static final String RUNNING = DocumentDeletionJobStatus.RUNNING.getCode();

    private final DocumentDeletionJobMapper mapper;
    private final MessagingProperties properties;
    private final ScheduledExecutorService executor;

    public DocumentDeletionLeaseHeartbeat(
            DocumentDeletionJobMapper mapper,
            MessagingProperties properties
    ) {
        this.mapper = mapper;
        this.properties = properties;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "docquery-deletion-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public Handle start(long jobId, String leaseOwner) {
        long periodMillis = Math.max(1_000, properties.getJobHeartbeat().toMillis());
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                () -> renew(jobId, leaseOwner),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
        return () -> future.cancel(false);
    }

    private void renew(long jobId, String leaseOwner) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            mapper.renewLease(
                    jobId, leaseOwner, now.plus(properties.getJobLease()), now, RUNNING
            );
        } catch (RuntimeException exception) {
            LOG.warn("Document deletion lease heartbeat failed; it will retry");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
