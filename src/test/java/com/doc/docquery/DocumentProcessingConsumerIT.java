package com.doc.docquery;

import com.doc.docquery.dto.CreateDocumentUploadDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.messaging.ConfirmedRabbitPublisher;
import com.doc.docquery.messaging.DocumentProcessingMessage;
import com.doc.docquery.messaging.RabbitMqTopology;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentIngestionProcessor;
import com.doc.docquery.service.DocumentProcessingException;
import com.doc.docquery.service.DocumentUploadAcceptanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 RabbitMQ 下验证手动 ACK、重复消息、租约、重试和最终 DLQ。 */
@Testcontainers
@Import(DocumentProcessingConsumerIT.ProcessorTestConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.object-storage.enabled=false",
                "docquery.messaging.infrastructure-enabled=true",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=true",
                "docquery.messaging.job-lease=10s",
                "docquery.messaging.job-heartbeat=1s",
                "docquery.messaging.retry-delay-1=1s",
                "docquery.messaging.retry-delay-2=1s",
                "docquery.messaging.retry-delay-3=1s",
                "docquery.messaging.confirm-timeout=5s"
        }
)
class DocumentProcessingConsumerIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            "rabbitmq:4.3.4-management"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DocumentUploadAcceptanceService acceptanceService;
    @Autowired
    private ConfirmedRabbitPublisher rabbitPublisher;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private TestProcessor processor;

    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        processor.reset();
        rabbitAdmin.initialize();
        purge(RabbitMqTopology.MAIN_QUEUE);
        purge(RabbitMqTopology.RETRY_QUEUE_1);
        purge(RabbitMqTopology.RETRY_QUEUE_2);
        purge(RabbitMqTopology.RETRY_QUEUE_3);
        purge(RabbitMqTopology.DLQ);
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
        tenantId = insertTenant();
        adminId = insertAdmin(tenantId);
        knowledgeBaseId = insertKnowledgeBase(tenantId);
    }

    @Test
    void successfulDuplicateMessageRunsProcessorOnlyOnce() throws Exception {
        AcceptedMessage accepted = accept("duplicate");
        processor.mode = Mode.SUCCESS;

        publish(accepted.message());
        awaitStatus(accepted.jobId(), "3", 10_000);
        publish(accepted.message());
        Thread.sleep(500);

        assertThat(processor.calls.get()).isEqualTo(1);
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                accepted.versionId()
        )).isEqualTo("1");
    }

    @Test
    void retryableFailureReturnsThroughTtlQueueThenSucceeds() throws Exception {
        AcceptedMessage accepted = accept("retry");
        processor.mode = Mode.RETRY_ONCE;

        publish(accepted.message());

        awaitStatus(accepted.jobId(), "3", 15_000);
        assertThat(processor.calls.get()).isEqualTo(2);
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                accepted.versionId()
        )).isEqualTo("1");
    }

    @Test
    void permanentFailureMarksFactsAndPublishesDlq() throws Exception {
        AcceptedMessage accepted = accept("permanent");
        processor.mode = Mode.PERMANENT_FAILURE;

        publish(accepted.message());

        awaitStatus(accepted.jobId(), "4", 10_000);
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                accepted.versionId()
        )).isEqualTo("3");
        assertThat(value(
                "SELECT failure_retryable FROM processing_job WHERE id=?",
                Boolean.class,
                accepted.jobId()
        )).isFalse();
        assertThat(rabbitTemplate.receive(RabbitMqTopology.DLQ, 5_000)).isNotNull();
    }

    @Test
    void retryExhaustionUsesAllThreeQueuesThenMarksFailedAndPublishesDlq()
            throws Exception {
        AcceptedMessage accepted = accept("retry-exhausted");
        processor.mode = Mode.ALWAYS_RETRY;

        publish(accepted.message());

        awaitStatus(accepted.jobId(), "4", 15_000);
        assertThat(processor.calls.get()).isEqualTo(4);
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                accepted.versionId()
        )).isEqualTo("3");
        assertThat(value(
                "SELECT failure_retryable FROM processing_job WHERE id=?",
                Boolean.class,
                accepted.jobId()
        )).isTrue();
        assertThat(rabbitTemplate.receive(RabbitMqTopology.DLQ, 5_000)).isNotNull();
    }

    private void publish(DocumentProcessingMessage message) {
        boolean confirmed = rabbitPublisher.publish(
                RabbitMqTopology.MAIN_EXCHANGE,
                RabbitMqTopology.MAIN_ROUTING_KEY,
                new tools.jackson.databind.ObjectMapper().writeValueAsBytes(message),
                Long.toString(message.eventId()),
                Map.of(RabbitMqTopology.RETRY_HEADER, 0)
        );
        assertThat(confirmed).isTrue();
    }

    private AcceptedMessage accept(String suffix) {
        CreateDocumentUploadDTO request = new CreateDocumentUploadDTO();
        request.setDocumentName("Consumer " + suffix);
        request.setIdempotencyKey("consumer-" + suffix);
        byte[] content = suffix.getBytes(StandardCharsets.UTF_8);
        StoredSourceObjectDTO source = new StoredSourceObjectDTO();
        source.setOriginalFilename(suffix + ".txt");
        source.setSourceFormat("3");
        source.setSourceBucket("docquery-source");
        source.setSourceObjectKey("source/" + tenantId + "/consumer/" + suffix);
        source.setSourceSizeBytes(content.length);
        source.setSourceSha256(sha256(content));
        source.setSourceContentType("text/plain");
        var accepted = acceptanceService.acceptNewDocument(
                tenantAdmin(),
                tenantId,
                knowledgeBaseId,
                request,
                source
        );
        long eventId = value(
                "SELECT id FROM outbox_event WHERE processing_job_id=?",
                Long.class,
                accepted.getProcessingJobId()
        );
        return new AcceptedMessage(
                accepted.getDocumentVersionId(),
                accepted.getProcessingJobId(),
                new DocumentProcessingMessage(
                        1,
                        eventId,
                        "1",
                        tenantId,
                        knowledgeBaseId,
                        accepted.getDocumentId(),
                        accepted.getDocumentVersionId(),
                        accepted.getProcessingJobId()
                )
        );
    }

    private void awaitStatus(long jobId, String expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String status = value(
                    "SELECT status FROM processing_job WHERE id=?",
                    String.class,
                    jobId
            );
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(value(
                "SELECT status FROM processing_job WHERE id=?",
                String.class,
                jobId
        )).isEqualTo(expected);
    }

    private void purge(String queue) {
        rabbitAdmin.purgeQueue(queue, false);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AdminPrincipal tenantAdmin() {
        return new AdminPrincipal(adminId, tenantId, "consumer.admin", null, "2", true);
    }

    private long insertTenant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO tenant (name,status,created_at,updated_at) VALUES ('Consumer Tenant','1',?,?)",
                now,
                now
        );
        return value("SELECT id FROM tenant WHERE name='Consumer Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    tenant_id,login_name,password_hash,role,status,created_at,updated_at
                ) VALUES (?,'consumer.admin','{bcrypt}test','2','1',?,?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value("SELECT id FROM admin_user WHERE login_name='consumer.admin'", Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    tenant_id,name,description,status,created_at,updated_at
                ) VALUES (?,'Consumer KB',NULL,'1',?,?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value(
                "SELECT id FROM knowledge_base WHERE tenant_id=? AND name='Consumer KB'",
                Long.class,
                ownerTenantId
        );
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private record AcceptedMessage(
            long versionId,
            long jobId,
            DocumentProcessingMessage message
    ) {
    }

    private enum Mode {
        SUCCESS,
        RETRY_ONCE,
        ALWAYS_RETRY,
        PERMANENT_FAILURE
    }

    /** 测试处理器显式提交 Job 成功；生产 N2.2 不提供该 Bean。 */
    static final class TestProcessor implements DocumentIngestionProcessor {

        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Mode mode = Mode.SUCCESS;

        TestProcessor(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void process(Context context) {
            int invocation = calls.incrementAndGet();
            if ((mode == Mode.RETRY_ONCE && invocation == 1)
                    || mode == Mode.ALWAYS_RETRY) {
                throw new DocumentProcessingException(
                        "TEST_RETRYABLE",
                        "Temporary test dependency failure",
                        true
                );
            }
            if (mode == Mode.PERMANENT_FAILURE) {
                throw new DocumentProcessingException(
                        "TEST_PERMANENT",
                        "Permanent test failure",
                        false
                );
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbcTemplate.update(
                    """
                    UPDATE processing_job
                    SET status='3', lease_owner=NULL, lease_until=NULL,
                        finished_at=?, updated_at=?
                    WHERE id=?
                    """,
                    now,
                    now,
                    context.job().getId()
            );
        }

        void reset() {
            calls.set(0);
            mode = Mode.SUCCESS;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProcessorTestConfig {

        @Bean
        TestProcessor documentIngestionProcessor(JdbcTemplate jdbcTemplate) {
            return new TestProcessor(jdbcTemplate);
        }
    }
}
