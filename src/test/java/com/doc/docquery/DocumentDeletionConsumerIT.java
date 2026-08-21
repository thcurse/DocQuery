package com.doc.docquery;

import com.doc.docquery.messaging.ConfirmedRabbitPublisher;
import com.doc.docquery.messaging.DocumentDeletionMessage;
import com.doc.docquery.messaging.DocumentDeletionRabbitMqTopology;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentDeletionException;
import com.doc.docquery.service.DocumentDeletionProcessor;
import com.doc.docquery.service.DocumentDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 RabbitMQ 下验证删除 Consumer 的 ACK、租约、三级重试和 DLQ。 */
@Testcontainers
@Import(DocumentDeletionConsumerIT.ProcessorTestConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.object-storage.enabled=false",
                "docquery.messaging.infrastructure-enabled=true",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=false",
                "docquery.messaging.deletion-listener-enabled=true",
                "docquery.messaging.job-lease=10s",
                "docquery.messaging.job-heartbeat=1s",
                "docquery.messaging.retry-delay-1=1s",
                "docquery.messaging.retry-delay-2=1s",
                "docquery.messaging.retry-delay-3=1s",
                "docquery.messaging.confirm-timeout=5s"
        }
)
class DocumentDeletionConsumerIT {

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
    private DocumentDeletionService deletionService;
    @Autowired
    private ConfirmedRabbitPublisher publisher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private TestDeletionProcessor processor;

    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        processor.reset();
        rabbitAdmin.initialize();
        purge(DocumentDeletionRabbitMqTopology.MAIN_QUEUE);
        purge(DocumentDeletionRabbitMqTopology.RETRY_QUEUE_1);
        purge(DocumentDeletionRabbitMqTopology.RETRY_QUEUE_2);
        purge(DocumentDeletionRabbitMqTopology.RETRY_QUEUE_3);
        purge(DocumentDeletionRabbitMqTopology.DLQ);
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM document_deletion_job");
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
    void successAndDuplicateRunProcessorOnlyOnce() throws Exception {
        Accepted accepted = accept("success");
        processor.mode = Mode.SUCCESS;

        publish(accepted.message());
        awaitJobStatus(accepted.jobId(), "3", 10_000);
        publish(accepted.message());
        Thread.sleep(500);

        assertThat(processor.calls.get()).isOne();
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                accepted.documentId())).isEqualTo("3");
        assertThat(rabbitTemplate.receive(DocumentDeletionRabbitMqTopology.DLQ, 100))
                .isNull();
    }

    @Test
    void retryableFailureUsesTtlQueueThenSucceeds() throws Exception {
        Accepted accepted = accept("retry-once");
        processor.mode = Mode.RETRY_ONCE;

        publish(accepted.message());

        awaitJobStatus(accepted.jobId(), "3", 15_000);
        assertThat(processor.calls.get()).isEqualTo(2);
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                accepted.documentId())).isEqualTo("3");
    }

    @Test
    void retryExhaustionMarksRetryableFailureAndKeepsDocumentInvisible()
            throws Exception {
        Accepted accepted = accept("retry-exhausted");
        processor.mode = Mode.ALWAYS_RETRY;

        publish(accepted.message());

        awaitJobStatus(accepted.jobId(), "4", 15_000);
        assertThat(processor.calls.get()).isEqualTo(4);
        assertThat(value("SELECT failure_retryable FROM document_deletion_job WHERE id=?",
                Boolean.class, accepted.jobId())).isTrue();
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                accepted.documentId())).isEqualTo("2");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                accepted.documentId())).isNull();
        assertThat(rabbitTemplate.receive(DocumentDeletionRabbitMqTopology.DLQ, 5_000))
                .isNotNull();
    }

    @Test
    void permanentFailureDoesNotRetryAndIsNotManuallyRetryable() throws Exception {
        Accepted accepted = accept("permanent");
        processor.mode = Mode.PERMANENT_FAILURE;

        publish(accepted.message());

        awaitJobStatus(accepted.jobId(), "4", 10_000);
        assertThat(processor.calls.get()).isOne();
        assertThat(value("SELECT failure_retryable FROM document_deletion_job WHERE id=?",
                Boolean.class, accepted.jobId())).isFalse();
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                accepted.documentId())).isEqualTo("2");
        assertThat(rabbitTemplate.receive(DocumentDeletionRabbitMqTopology.DLQ, 5_000))
                .isNotNull();
    }

    private Accepted accept(String suffix) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO document (
                    tenant_id, knowledge_base_id, name, active_name, status,
                    active_version_id, latest_version_id, created_by_admin_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, '1', NULL, NULL, ?, ?, ?)
                """, tenantId, knowledgeBaseId, "Delete " + suffix,
                "Delete " + suffix, adminId, now, now);
        long documentId = value("SELECT id FROM document WHERE active_name=?",
                Long.class, "Delete " + suffix);
        String hash = "%064x".formatted(documentId);
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    tenant_id, document_id, version_no, status, source_format,
                    original_filename, source_bucket, source_object_key,
                    source_size_bytes, source_sha256, source_content_type,
                    idempotency_key_hash, request_fingerprint, accepted_by_admin_id,
                    ready_at, created_at, updated_at
                ) VALUES (?, ?, 1, '2', '3', 'delete.txt', 'docquery-source', ?,
                          10, ?, 'text/plain', ?, ?, ?, ?, ?, ?)
                """, tenantId, documentId, "source/delete/" + documentId,
                hash, hash, hash, adminId, now, now, now);
        long versionId = value("SELECT id FROM document_version WHERE document_id=?",
                Long.class, documentId);
        jdbcTemplate.update("""
                INSERT INTO processing_job (
                    tenant_id, document_version_id, job_type, attempt_no, status,
                    started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, '1', 1, '3', ?, ?, ?, ?)
                """, tenantId, versionId, now, now, now, now);
        jdbcTemplate.update("""
                UPDATE document SET active_version_id=?, latest_version_id=?, updated_at=?
                WHERE id=?
                """, versionId, versionId, now, documentId);

        var accepted = deletionService.deleteDocument(
                new AdminPrincipal(adminId, tenantId, "deletion-consumer.admin", null, "2", true),
                tenantId, knowledgeBaseId, documentId, "delete-" + suffix
        );
        long eventId = value(
                "SELECT id FROM outbox_event WHERE document_deletion_job_id=?",
                Long.class,
                accepted.getDeletionJobId()
        );
        return new Accepted(
                documentId,
                accepted.getDeletionJobId(),
                new DocumentDeletionMessage(
                        1, eventId, "2", tenantId, knowledgeBaseId,
                        documentId, accepted.getDeletionJobId()
                )
        );
    }

    private void publish(DocumentDeletionMessage message) {
        assertThat(publisher.publish(
                DocumentDeletionRabbitMqTopology.MAIN_EXCHANGE,
                DocumentDeletionRabbitMqTopology.MAIN_ROUTING_KEY,
                objectMapper.writeValueAsBytes(message),
                Long.toString(message.eventId()),
                Map.of(DocumentDeletionRabbitMqTopology.RETRY_HEADER, 0)
        )).isTrue();
    }

    private void awaitJobStatus(long jobId, String expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(value(
                    "SELECT status FROM document_deletion_job WHERE id=?",
                    String.class,
                    jobId
            ))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for deletion job status " + expected);
    }

    private void purge(String queue) {
        rabbitAdmin.purgeQueue(queue, false);
    }

    private long insertTenant() {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO tenant (name,status,created_at,updated_at)
                VALUES ('Deletion Consumer Tenant','1',?,?)
                """, now, now);
        return value("SELECT id FROM tenant WHERE name='Deletion Consumer Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO admin_user (
                    tenant_id,login_name,password_hash,role,status,created_at,updated_at
                ) VALUES (?,'deletion-consumer.admin','{bcrypt}test','2','1',?,?)
                """, ownerTenantId, now, now);
        return value("SELECT id FROM admin_user WHERE login_name='deletion-consumer.admin'",
                Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (
                    tenant_id,name,description,status,created_at,updated_at
                ) VALUES (?,'Deletion Consumer KB',NULL,'1',?,?)
                """, ownerTenantId, now, now);
        return value("SELECT id FROM knowledge_base WHERE tenant_id=?", Long.class,
                ownerTenantId);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private record Accepted(long documentId, long jobId, DocumentDeletionMessage message) {
    }

    enum Mode {
        SUCCESS,
        RETRY_ONCE,
        ALWAYS_RETRY,
        PERMANENT_FAILURE
    }

    static final class TestDeletionProcessor implements DocumentDeletionProcessor {
        private final JdbcTemplate jdbcTemplate;
        final AtomicInteger calls = new AtomicInteger();
        volatile Mode mode = Mode.SUCCESS;

        TestDeletionProcessor(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void process(Context context) {
            int call = calls.incrementAndGet();
            if (mode == Mode.RETRY_ONCE && call == 1 || mode == Mode.ALWAYS_RETRY) {
                throw new DocumentDeletionException(
                        "TEST_DELETION_RETRYABLE", "retryable", true, null
                );
            }
            if (mode == Mode.PERMANENT_FAILURE) {
                throw new DocumentDeletionException(
                        "TEST_DELETION_PERMANENT", "permanent", false, null
                );
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbcTemplate.update("""
                    UPDATE document_deletion_job
                    SET status='3', lease_owner=NULL, lease_until=NULL,
                        finished_at=?, updated_at=? WHERE id=?
                    """, now, now, context.job().getId());
            jdbcTemplate.update("""
                    UPDATE document
                    SET status='3', active_name=NULL, active_version_id=NULL,
                        deleted_at=?, updated_at=? WHERE id=?
                    """, now, now, context.document().getId());
        }

        void reset() {
            calls.set(0);
            mode = Mode.SUCCESS;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProcessorTestConfig {
        @Bean
        TestDeletionProcessor testDeletionProcessor(JdbcTemplate jdbcTemplate) {
            return new TestDeletionProcessor(jdbcTemplate);
        }
    }
}
