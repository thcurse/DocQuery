package com.doc.docquery;

import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.dto.CreateDocumentUploadDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.job.OutboxPublisher;
import com.doc.docquery.mapper.OutboxEventMapper;
import com.doc.docquery.messaging.ConfirmedRabbitPublisher;
import com.doc.docquery.messaging.DocumentProcessingMessage;
import com.doc.docquery.messaging.DocumentDeletionMessage;
import com.doc.docquery.messaging.DocumentDeletionRabbitMqTopology;
import com.doc.docquery.messaging.RabbitMqTopology;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadAcceptanceService;
import com.doc.docquery.service.DocumentDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** MySQL Outbox 租约与 RabbitMQ Confirm/Return 的真实集成验证。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.object-storage.enabled=false",
                "docquery.messaging.infrastructure-enabled=true",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=false",
                "docquery.messaging.confirm-timeout=5s"
        }
)
class OutboxPublisherRabbitMqIT {

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
    private DocumentDeletionService deletionService;
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private ConfirmedRabbitPublisher confirmedRabbitPublisher;
    @Autowired
    private MessagingProperties messagingProperties;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    private OutboxPublisher publisher;
    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        publisher = new OutboxPublisher(
                outboxEventMapper,
                confirmedRabbitPublisher,
                messagingProperties,
                objectMapper,
                transactionManager
        );
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(RabbitMqTopology.MAIN_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqTopology.DLQ, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.MAIN_QUEUE, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.DLQ, false);
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
    void confirmedPublishMarksSentAndDeliversOnlyStableIds() {
        AcceptedIds accepted = accept("confirmed");

        publisher.publishAvailable();

        assertThat(value(
                "SELECT status FROM outbox_event WHERE id=?",
                String.class,
                accepted.eventId()
        )).isEqualTo("3");
        Message raw = rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 5_000);
        assertThat(raw).isNotNull();
        DocumentProcessingMessage message = objectMapper.readValue(
                raw.getBody(),
                DocumentProcessingMessage.class
        );
        assertThat(message.eventId()).isEqualTo(accepted.eventId());
        assertThat(message.documentVersionId()).isEqualTo(accepted.versionId());
        assertThat(message.processingJobId()).isEqualTo(accepted.jobId());
        String body = new String(raw.getBody(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("source/", "sha256", "secret");
    }

    @Test
    void deletionEventRoutesToIndependentQueueWithStableIds() {
        AcceptedIds ingestion = accept("deletion-route");
        long documentId = value(
                "SELECT document_id FROM document_version WHERE id=?",
                Long.class,
                ingestion.versionId()
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE processing_job SET status='3', finished_at=?, updated_at=? WHERE id=?",
                now, now, ingestion.jobId()
        );
        jdbcTemplate.update(
                "UPDATE document_version SET status='2', ready_at=?, updated_at=? WHERE id=?",
                now, now, ingestion.versionId()
        );
        jdbcTemplate.update(
                "UPDATE document SET active_version_id=?, updated_at=? WHERE id=?",
                ingestion.versionId(), now, documentId
        );
        // 先消费既有入库 Outbox，避免本轮同时领取两个事件干扰断言。
        jdbcTemplate.update("DELETE FROM outbox_event WHERE id=?", ingestion.eventId());

        var accepted = deletionService.deleteDocument(
                tenantAdmin(), tenantId, knowledgeBaseId, documentId, "delete-route"
        );
        publisher.publishAvailable();

        assertThat(value(
                "SELECT status FROM outbox_event WHERE document_deletion_job_id=?",
                String.class,
                accepted.getDeletionJobId()
        )).isEqualTo("3");
        Message raw = rabbitTemplate.receive(
                DocumentDeletionRabbitMqTopology.MAIN_QUEUE, 5_000
        );
        assertThat(raw).isNotNull();
        DocumentDeletionMessage message = objectMapper.readValue(
                raw.getBody(), DocumentDeletionMessage.class
        );
        assertThat(message.documentId()).isEqualTo(documentId);
        assertThat(message.documentDeletionJobId()).isEqualTo(accepted.getDeletionJobId());
        assertThat(new String(raw.getBody(), StandardCharsets.UTF_8))
                .doesNotContain("source/", "sha256", "secret");
        assertThat(rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 100)).isNull();
    }

    @Test
    void returnedMessageStaysPendingAndRecoversAfterRouteReturns() {
        AcceptedIds accepted = accept("no-route");
        rabbitAdmin.deleteQueue(RabbitMqTopology.MAIN_QUEUE);

        publisher.publishAvailable();

        assertThat(value(
                "SELECT status FROM outbox_event WHERE id=?",
                String.class,
                accepted.eventId()
        )).isEqualTo("1");
        assertThat(value(
                "SELECT last_error_code FROM outbox_event WHERE id=?",
                String.class,
                accepted.eventId()
        )).isEqualTo("RABBITMQ_PUBLISH_FAILED");

        rabbitAdmin.initialize();
        jdbcTemplate.update(
                "UPDATE outbox_event SET available_at=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                accepted.eventId()
        );
        publisher.publishAvailable();

        assertThat(value(
                "SELECT status FROM outbox_event WHERE id=?",
                String.class,
                accepted.eventId()
        )).isEqualTo("3");
        assertThat(rabbitTemplate.receive(RabbitMqTopology.MAIN_QUEUE, 5_000))
                .isNotNull();
    }

    @Test
    void leasePreventsConcurrentClaimAndExpiredLeaseIsRecovered() {
        AcceptedIds accepted = accept("lease");

        assertThat(publisher.claimBatch()).hasSize(1);
        assertThat(publisher.claimBatch()).isEmpty();

        jdbcTemplate.update(
                "UPDATE outbox_event SET locked_until=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                accepted.eventId()
        );
        assertThat(publisher.claimBatch()).hasSize(1);
    }

    private AcceptedIds accept(String suffix) {
        CreateDocumentUploadDTO request = new CreateDocumentUploadDTO();
        request.setDocumentName("Outbox " + suffix);
        request.setIdempotencyKey("outbox-" + suffix);
        byte[] content = suffix.getBytes(StandardCharsets.UTF_8);
        StoredSourceObjectDTO source = new StoredSourceObjectDTO();
        source.setOriginalFilename(suffix + ".txt");
        source.setSourceFormat("3");
        source.setSourceBucket("docquery-source");
        source.setSourceObjectKey("source/" + tenantId + "/test/" + suffix);
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
        return new AcceptedIds(
                eventId,
                accepted.getDocumentVersionId(),
                accepted.getProcessingJobId()
        );
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
        return new AdminPrincipal(adminId, tenantId, "outbox.admin", null, "2", true);
    }

    private long insertTenant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO tenant (name,status,created_at,updated_at) VALUES ('Outbox Tenant','1',?,?)",
                now,
                now
        );
        return value("SELECT id FROM tenant WHERE name='Outbox Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    tenant_id,login_name,password_hash,role,status,created_at,updated_at
                ) VALUES (?,'outbox.admin','{bcrypt}test','2','1',?,?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value("SELECT id FROM admin_user WHERE login_name='outbox.admin'", Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    tenant_id,name,description,status,created_at,updated_at
                ) VALUES (?,'Outbox KB',NULL,'1',?,?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value(
                "SELECT id FROM knowledge_base WHERE tenant_id=? AND name='Outbox KB'",
                Long.class,
                ownerTenantId
        );
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private record AcceptedIds(long eventId, long versionId, long jobId) {
    }
}
