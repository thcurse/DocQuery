package com.doc.docquery.messaging;

import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.enums.ProcessingJobStatus;
import com.doc.docquery.mapper.ProcessingJobMapper;
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

/** 长任务处理期间按 owner 条件续租，旧实例不能延长新实例租约。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.messaging",
        name = "listener-enabled",
        havingValue = "true"
)
public class ProcessingLeaseHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(
            ProcessingLeaseHeartbeat.class
    );
    private static final String RUNNING = ProcessingJobStatus.RUNNING.getCode();

    private final ProcessingJobMapper processingJobMapper;
    private final MessagingProperties properties;
    private final ScheduledExecutorService executor;

    public ProcessingLeaseHeartbeat(
            ProcessingJobMapper processingJobMapper,
            MessagingProperties properties
    ) {
        this.processingJobMapper = processingJobMapper;
        this.properties = properties;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "docquery-processing-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** 启动当前消息的续租任务，关闭 Handle 即停止续租。 */
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
            processingJobMapper.renewLease(
                    jobId,
                    leaseOwner,
                    now.plus(properties.getJobLease()),
                    now,
                    RUNNING
            );
        } catch (RuntimeException exception) {
            // ScheduledExecutor 遇到未捕获异常会停止后续执行；这里保留下一次续租机会。
            LOG.warn("Processing job lease heartbeat failed; it will retry");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 使 Listener 可以用 try-with-resources 确保停止心跳。 */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
