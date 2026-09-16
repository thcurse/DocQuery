package com.doc.docquery;

import com.doc.docquery.dto.CreateDocumentUploadDTO;
import com.doc.docquery.dto.CreateDocumentVersionDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadAcceptanceService;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentUploadAcceptanceIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    private DocumentUploadAcceptanceService uploadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantAId;
    private long tenantBId;
    private long knowledgeBaseAId;
    private long knowledgeBaseBId;
    private long adminAId;
    private long adminBId;
    private long platformAdminId;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");

        tenantAId = insertTenant("Document Tenant A");
        tenantBId = insertTenant("Document Tenant B");
        adminAId = insertAdmin(tenantAId, "document-a.admin", "2");
        adminBId = insertAdmin(tenantBId, "document-b.admin", "2");
        platformAdminId = insertAdmin(null, "document.platform", "1");
        knowledgeBaseAId = insertKnowledgeBase(tenantAId, "Service Manuals");
        knowledgeBaseBId = insertKnowledgeBase(tenantBId, "Private Manuals");
    }

    @Test
    void firstUploadCreatesAllFactsAtomicallyWithoutActiveVersion() {
        String rawKey = "first-upload-key";
        StoredSourceObjectDTO source = source(
                "S1维修手册.pdf",
                "1",
                "tenant-a/s1/v1.pdf",
                "s1-version-1"
        );

        DocumentUploadAcceptedVO accepted = uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                createDocument("  S1设备维修手册  ", rawKey),
                source
        );

        assertThat(accepted.getVersionNo()).isEqualTo(1);
        assertThat(accepted.getDocumentStatus()).isEqualTo("1");
        assertThat(accepted.getVersionStatus()).isEqualTo("1");
        assertThat(accepted.getJobStatus()).isEqualTo("1");
        assertThat(accepted.getAcceptedAt().getOffset()).isEqualTo(ZoneOffset.UTC);

        assertThat(value(
                "SELECT name FROM document WHERE id = ?",
                String.class,
                accepted.getDocumentId()
        )).isEqualTo("S1设备维修手册");
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id = ?",
                Long.class,
                accepted.getDocumentId()
        )).isNull();
        assertThat(value(
                "SELECT latest_version_id FROM document WHERE id = ?",
                Long.class,
                accepted.getDocumentId()
        )).isEqualTo(accepted.getDocumentVersionId());
        assertThat(value(
                "SELECT idempotency_key_hash FROM document_version WHERE id = ?",
                String.class,
                accepted.getDocumentVersionId()
        )).hasSize(64).isNotEqualTo(rawKey);

        String payload = value(
                "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE processing_job_id = ?",
                String.class,
                accepted.getProcessingJobId()
        );
        assertThat(payload)
                .contains("\"tenantId\": " + tenantAId)
                .contains("\"knowledgeBaseId\": " + knowledgeBaseAId)
                .contains("\"documentId\": " + accepted.getDocumentId())
                .contains("\"documentVersionId\": " + accepted.getDocumentVersionId())
                .contains("\"processingJobId\": " + accepted.getProcessingJobId())
                .doesNotContain(source.getSourceObjectKey(), source.getSourceSha256());
        assertCounts(1, 1, 1, 1);
    }

    @Test
    void idempotentReplayReturnsOriginalFactsAndDifferentFingerprintConflicts() {
        CreateDocumentUploadDTO request = createDocument("幂等手册", "same-key");
        StoredSourceObjectDTO source = source(
                "manual.pdf",
                "1",
                "tenant-a/idempotent/v1.pdf",
                "idempotent-content"
        );
        DocumentUploadAcceptedVO first = uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                request,
                source
        );

        DocumentUploadAcceptedVO replay = uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                request,
                source
        );

        assertThat(replay.getDocumentId()).isEqualTo(first.getDocumentId());
        assertThat(replay.getDocumentVersionId()).isEqualTo(first.getDocumentVersionId());
        assertThat(replay.getProcessingJobId()).isEqualTo(first.getProcessingJobId());
        assertCounts(1, 1, 1, 1);

        BusinessException conflict = catchBusiness(() -> uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                createDocument("另一份手册", "same-key"),
                source(
                        "other.pdf",
                        "1",
                        "tenant-a/idempotent/other.pdf",
                        "different-content"
                )
        ));
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertCounts(1, 1, 1, 1);
    }

    @Test
    void newVersionKeepsReadyVersionActiveAndBlocksAnotherProcessingVersion() {
        DocumentUploadAcceptedVO first = acceptFirstReadyVersion(
                "Versioned Manual",
                "versioned-v1",
                "versioned-content-v1"
        );

        DocumentUploadAcceptedVO second = uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                createVersion("versioned-v2"),
                source(
                        "manual-v2.pdf",
                        "1",
                        "tenant-a/versioned/v2.pdf",
                        "versioned-content-v2"
                )
        );

        assertThat(second.getVersionNo()).isEqualTo(2);
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id = ?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(first.getDocumentVersionId());
        assertThat(value(
                "SELECT latest_version_id FROM document WHERE id = ?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(second.getDocumentVersionId());

        BusinessException inProgress = catchBusiness(() -> uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                createVersion("versioned-v3"),
                source(
                        "manual-v3.pdf",
                        "1",
                        "tenant-a/versioned/v3.pdf",
                        "versioned-content-v3"
                )
        ));
        assertThat(inProgress.code()).isEqualTo("DOCUMENT_VERSION_IN_PROGRESS");
        assertCounts(1, 2, 2, 2);
    }

    @Test
    void rebuildAllowsExactSourceCopyAndKeepsOldVersionActive() {
        String content = "rebuild-same-content";
        DocumentUploadAcceptedVO first = acceptFirstReadyVersion(
                "Rebuild Manual",
                "rebuild-v1",
                content
        );
        var rebuildSource = uploadService.loadRebuildSource(
                tenantAdminA(), tenantAId, knowledgeBaseAId, first.getDocumentId()
        );

        DocumentUploadAcceptedVO second = uploadService.acceptRebuild(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                rebuildSource.sourceVersionId(),
                "rebuild-command",
                source(
                        rebuildSource.originalFilename(),
                        rebuildSource.sourceFormat(),
                        "tenant-a/rebuild/copied.pdf",
                        content
                )
        );

        assertThat(second.getVersionNo()).isEqualTo(2);
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id = ?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(first.getDocumentVersionId());
        assertThat(value(
                "SELECT latest_version_id FROM document WHERE id = ?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(second.getDocumentVersionId());
        assertThat(value(
                "SELECT source_sha256 FROM document_version WHERE id = ?",
                String.class,
                second.getDocumentVersionId()
        )).isEqualTo(rebuildSource.sourceSha256());
        assertThat(value(
                "SELECT source_object_key FROM document_version WHERE id = ?",
                String.class,
                second.getDocumentVersionId()
        )).isNotEqualTo(rebuildSource.sourceObjectKey());

        DocumentUploadAcceptedVO replay = uploadService.findRebuildReplay(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                "rebuild-command"
        );
        assertThat(replay.getDocumentVersionId()).isEqualTo(second.getDocumentVersionId());
        assertCounts(1, 2, 2, 2);
    }

    @Test
    void rebuildRejectsChangedCopyAndExistingProcessingCandidate() {
        String content = "rebuild-guard-content";
        DocumentUploadAcceptedVO first = acceptFirstReadyVersion(
                "Rebuild Guard",
                "rebuild-guard-v1",
                content
        );
        var rebuildSource = uploadService.loadRebuildSource(
                tenantAdminA(), tenantAId, knowledgeBaseAId, first.getDocumentId()
        );
        StoredSourceObjectDTO changed = source(
                rebuildSource.originalFilename(),
                rebuildSource.sourceFormat(),
                "tenant-a/rebuild/changed.pdf",
                "changed-content"
        );

        BusinessException mismatch = catchBusiness(() -> uploadService.acceptRebuild(
                tenantAdminA(), tenantAId, knowledgeBaseAId, first.getDocumentId(),
                rebuildSource.sourceVersionId(), "rebuild-changed", changed
        ));
        assertThat(mismatch.code()).isEqualTo("DOCUMENT_REBUILD_SOURCE_MISMATCH");
        assertCounts(1, 1, 1, 1);

        uploadService.acceptRebuild(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                rebuildSource.sourceVersionId(),
                "rebuild-first",
                source(
                        rebuildSource.originalFilename(),
                        rebuildSource.sourceFormat(),
                        "tenant-a/rebuild/first.pdf",
                        content
                )
        );
        BusinessException inProgress = catchBusiness(() ->
                uploadService.loadRebuildSource(
                        tenantAdminA(), tenantAId, knowledgeBaseAId, first.getDocumentId()
                ));
        assertThat(inProgress.code()).isEqualTo("DOCUMENT_VERSION_IN_PROGRESS");
        assertCounts(1, 2, 2, 2);
    }

    @Test
    void failedLatestVersionRequiresRetryForSameContentButAllowsDifferentContent() {
        DocumentUploadAcceptedVO first = acceptFirstReadyVersion(
                "Failure Manual",
                "failure-v1",
                "failure-content-v1"
        );
        StoredSourceObjectDTO failedSource = source(
                "failure-v2.pdf",
                "1",
                "tenant-a/failure/v2.pdf",
                "failure-content-v2"
        );
        DocumentUploadAcceptedVO failed = uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                createVersion("failure-v2"),
                failedSource
        );
        markFailed(failed.getDocumentVersionId(), failed.getProcessingJobId(), true);

        StoredSourceObjectDTO repeatedFailedContent = source(
                "failure-v2.pdf",
                "1",
                "tenant-a/failure/v2-reupload.pdf",
                "failure-content-v2"
        );
        BusinessException retryRequired = catchBusiness(() -> uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                createVersion("failure-v2-reupload"),
                repeatedFailedContent
        ));
        assertThat(retryRequired.code()).isEqualTo("FAILED_VERSION_RETRY_REQUIRED");

        DocumentUploadAcceptedVO third = uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                createVersion("failure-v3"),
                source(
                        "failure-v3.pdf",
                        "1",
                        "tenant-a/failure/v3.pdf",
                        "failure-content-v3"
                )
        );
        assertThat(third.getVersionNo()).isEqualTo(3);
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id = ?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(first.getDocumentVersionId());
        assertCounts(1, 3, 3, 3);
    }

    @Test
    void nonRetryableFailedLatestVersionAllowsSameContentAsANewVersion() {
        DocumentUploadAcceptedVO failed = uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                createDocument("Non-retryable Manual", "non-retryable-v1"),
                source(
                        "non-retryable.pdf",
                        "1",
                        "tenant-a/non-retryable/v1.pdf",
                        "same-failed-content"
                )
        );
        markFailed(failed.getDocumentVersionId(), failed.getProcessingJobId(), false);

        DocumentUploadAcceptedVO second = uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                failed.getDocumentId(),
                createVersion("non-retryable-v2"),
                source(
                        "non-retryable.pdf",
                        "1",
                        "tenant-a/non-retryable/v2.pdf",
                        "same-failed-content"
                )
        );

        assertThat(second.getVersionNo()).isEqualTo(2);
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id = ?",
                Long.class,
                failed.getDocumentId()
        )).isNull();
        assertCounts(1, 2, 2, 2);
    }

    @Test
    void unchangedActiveContentAndDuplicateNameAreRejectedWhileCaseVariantIsAllowed() {
        DocumentUploadAcceptedVO first = acceptFirstReadyVersion(
                "Service Manual",
                "unchanged-v1",
                "same-content"
        );

        BusinessException unchanged = catchBusiness(() -> uploadService.acceptNewVersion(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                first.getDocumentId(),
                createVersion("unchanged-v2"),
                source(
                        "same-v2.pdf",
                        "1",
                        "tenant-a/unchanged/v2.pdf",
                        "same-content"
                )
        ));
        assertThat(unchanged.code()).isEqualTo("DOCUMENT_CONTENT_UNCHANGED");

        BusinessException duplicateName = catchBusiness(() -> uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                createDocument("Service Manual", "duplicate-name"),
                source(
                        "another.pdf",
                        "1",
                        "tenant-a/names/another.pdf",
                        "another-content"
                )
        ));
        assertThat(duplicateName.code()).isEqualTo("DOCUMENT_NAME_CONFLICT");

        DocumentUploadAcceptedVO caseVariant = uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                createDocument("service manual", "case-variant"),
                source(
                        "lowercase.pdf",
                        "1",
                        "tenant-a/names/lowercase.pdf",
                        "lowercase-content"
                )
        );
        assertThat(caseVariant.getDocumentId()).isNotEqualTo(first.getDocumentId());
        assertCounts(2, 2, 2, 2);
    }

    @Test
    void tenantRoleResourceStateAndCrossTenantDocumentAreEnforced() {
        StoredSourceObjectDTO source = source(
                "scope.pdf",
                "1",
                "tenant-a/scope/v1.pdf",
                "scope-content"
        );
        CreateDocumentUploadDTO request = createDocument("Scope Manual", "scope-key");

        assertCode(
                () -> uploadService.acceptNewDocument(
                        platformAdmin(), tenantAId, knowledgeBaseAId, request, source
                ),
                "TENANT_RESOURCE_MANAGEMENT_FORBIDDEN"
        );
        assertCode(
                () -> uploadService.acceptNewDocument(
                        tenantAdminB(), tenantAId, knowledgeBaseAId, request, source
                ),
                "TENANT_SCOPE_FORBIDDEN"
        );
        assertCode(
                () -> uploadService.acceptNewDocument(
                        tenantAdminA(), tenantAId, knowledgeBaseBId, request, source
                ),
                "KNOWLEDGE_BASE_NOT_FOUND"
        );

        jdbcTemplate.update(
                "UPDATE knowledge_base SET status = '2' WHERE id = ?",
                knowledgeBaseAId
        );
        assertCode(
                () -> uploadService.acceptNewDocument(
                        tenantAdminA(), tenantAId, knowledgeBaseAId, request, source
                ),
                "KNOWLEDGE_BASE_NOT_FOUND"
        );
        jdbcTemplate.update(
                "UPDATE knowledge_base SET status = '1' WHERE id = ?",
                knowledgeBaseAId
        );

        DocumentUploadAcceptedVO otherTenantDocument = uploadService.acceptNewDocument(
                tenantAdminB(),
                tenantBId,
                knowledgeBaseBId,
                createDocument("Other Tenant Manual", "other-tenant"),
                source(
                        "other-tenant.pdf",
                        "1",
                        "tenant-b/other/v1.pdf",
                        "other-tenant-content"
                )
        );
        assertCode(
                () -> uploadService.acceptNewVersion(
                        tenantAdminA(),
                        tenantAId,
                        knowledgeBaseAId,
                        otherTenantDocument.getDocumentId(),
                        createVersion("cross-document"),
                        source(
                                "cross.pdf",
                                "1",
                                "tenant-a/cross/v2.pdf",
                                "cross-content"
                        )
                ),
                "DOCUMENT_NOT_FOUND"
        );
        assertCounts(1, 1, 1, 1);
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesOneBusinessResult() throws Exception {
        CreateDocumentUploadDTO request = createDocument(
                "Concurrent Manual",
                "concurrent-same-key"
        );
        StoredSourceObjectDTO source = source(
                "concurrent.pdf",
                "1",
                "tenant-a/concurrent/v1.pdf",
                "concurrent-content"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<DocumentUploadAcceptedVO>> futures = List.of(
                    executor.submit(() -> concurrentFirstUpload(ready, start, request, source)),
                    executor.submit(() -> concurrentFirstUpload(ready, start, request, source))
            );
            ready.await();
            start.countDown();

            DocumentUploadAcceptedVO first = futures.get(0).get();
            DocumentUploadAcceptedVO second = futures.get(1).get();
            assertThat(second.getDocumentId()).isEqualTo(first.getDocumentId());
            assertThat(second.getDocumentVersionId()).isEqualTo(first.getDocumentVersionId());
            assertThat(second.getProcessingJobId()).isEqualTo(first.getProcessingJobId());
            assertCounts(1, 1, 1, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentNewVersionsCreateOnlyOneProcessingCandidate()
            throws Exception {
        DocumentUploadAcceptedVO first = acceptFirstReadyVersion(
                "Concurrent Version Manual",
                "concurrent-version-v1",
                "concurrent-version-content-v1"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> concurrentNewVersion(
                            ready,
                            start,
                            first.getDocumentId(),
                            "concurrent-version-v2-a",
                            "tenant-a/concurrent-version/v2-a.pdf",
                            "concurrent-version-content-v2-a"
                    )),
                    executor.submit(() -> concurrentNewVersion(
                            ready,
                            start,
                            first.getDocumentId(),
                            "concurrent-version-v2-b",
                            "tenant-a/concurrent-version/v2-b.pdf",
                            "concurrent-version-content-v2-b"
                    ))
            );
            ready.await();
            start.countDown();

            List<Object> results = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(results.stream().filter(DocumentUploadAcceptedVO.class::isInstance))
                    .hasSize(1);
            List<BusinessException> failures = results.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .toList();
            assertThat(failures).singleElement()
                    .extracting(BusinessException::code)
                    .isEqualTo("DOCUMENT_VERSION_IN_PROGRESS");
            assertCounts(1, 2, 2, 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outboxFailureRollsBackDocumentVersionJobAndPointers() {
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_outbox_insert
                BEFORE INSERT ON outbox_event
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test outbox failure'
                """);
        try {
            assertThatThrownBy(() -> uploadService.acceptNewDocument(
                    tenantAdminA(),
                    tenantAId,
                    knowledgeBaseAId,
                    createDocument("Rollback Manual", "rollback-key"),
                    source(
                            "rollback.pdf",
                            "1",
                            "tenant-a/rollback/v1.pdf",
                            "rollback-content"
                    )
            )).isInstanceOf(DataAccessException.class);
            assertCounts(0, 0, 0, 0);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_outbox_insert");
        }
    }

    @Test
    void sourceDescriptorValidationRejectsInvalidInputBeforeDatabaseWrites() {
        StoredSourceObjectDTO invalid = source(
                "manual.txt",
                "1",
                "tenant-a/invalid/v1.pdf",
                "invalid-content"
        );
        assertCode(
                () -> uploadService.acceptNewDocument(
                        tenantAdminA(),
                        tenantAId,
                        knowledgeBaseAId,
                        createDocument("Invalid Manual", "invalid-key"),
                        invalid
                ),
                "VALIDATION_FAILED"
        );
        assertCounts(0, 0, 0, 0);
    }

    private DocumentUploadAcceptedVO concurrentFirstUpload(
            CountDownLatch ready,
            CountDownLatch start,
            CreateDocumentUploadDTO request,
            StoredSourceObjectDTO source
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                request,
                source
        );
    }

    private Object concurrentNewVersion(
            CountDownLatch ready,
            CountDownLatch start,
            long documentId,
            String idempotencyKey,
            String objectKey,
            String content
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return uploadService.acceptNewVersion(
                    tenantAdminA(),
                    tenantAId,
                    knowledgeBaseAId,
                    documentId,
                    createVersion(idempotencyKey),
                    source("concurrent-v2.pdf", "1", objectKey, content)
            );
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private DocumentUploadAcceptedVO acceptFirstReadyVersion(
            String documentName,
            String idempotencyKey,
            String content
    ) {
        DocumentUploadAcceptedVO accepted = uploadService.acceptNewDocument(
                tenantAdminA(),
                tenantAId,
                knowledgeBaseAId,
                createDocument(documentName, idempotencyKey),
                source(
                        documentName.replace(' ', '-') + ".pdf",
                        "1",
                        "tenant-a/ready/" + idempotencyKey + ".pdf",
                        content
                )
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                UPDATE document_version
                SET status = '2', ready_at = ?, updated_at = ?
                WHERE id = ?
                """,
                now,
                now,
                accepted.getDocumentVersionId()
        );
        jdbcTemplate.update(
                """
                UPDATE processing_job
                SET status = '3', finished_at = ?, updated_at = ?
                WHERE id = ?
                """,
                now,
                now,
                accepted.getProcessingJobId()
        );
        jdbcTemplate.update(
                """
                UPDATE document
                SET active_version_id = ?, updated_at = ?
                WHERE id = ?
                """,
                accepted.getDocumentVersionId(),
                now,
                accepted.getDocumentId()
        );
        return accepted;
    }

    private void markFailed(long versionId, long jobId, boolean retryable) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                UPDATE document_version
                SET status = '3', failure_code = 'TEST_FAILURE',
                    failure_message = 'test failure', failed_at = ?, updated_at = ?
                WHERE id = ?
                """,
                now,
                now,
                versionId
        );
        jdbcTemplate.update(
                """
                UPDATE processing_job
                SET status = '4', failure_code = 'TEST_FAILURE',
                    failure_message = 'test failure', failure_retryable = ?,
                    finished_at = ?, updated_at = ?
                WHERE id = ?
                """,
                retryable,
                now,
                now,
                jobId
        );
    }

    private CreateDocumentUploadDTO createDocument(String name, String key) {
        CreateDocumentUploadDTO dto = new CreateDocumentUploadDTO();
        dto.setDocumentName(name);
        dto.setIdempotencyKey(key);
        return dto;
    }

    private CreateDocumentVersionDTO createVersion(String key) {
        CreateDocumentVersionDTO dto = new CreateDocumentVersionDTO();
        dto.setIdempotencyKey(key);
        return dto;
    }

    private StoredSourceObjectDTO source(
            String filename,
            String format,
            String objectKey,
            String content
    ) {
        StoredSourceObjectDTO source = new StoredSourceObjectDTO();
        source.setOriginalFilename(filename);
        source.setSourceFormat(format);
        source.setSourceBucket("docquery-source");
        source.setSourceObjectKey(objectKey);
        source.setSourceSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
        source.setSourceSha256(sha256(content));
        source.setSourceContentType("application/octet-stream");
        return source;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AdminPrincipal tenantAdminA() {
        return principal(adminAId, tenantAId, "document-a.admin", "2");
    }

    private AdminPrincipal tenantAdminB() {
        return principal(adminBId, tenantBId, "document-b.admin", "2");
    }

    private AdminPrincipal platformAdmin() {
        return principal(platformAdminId, null, "document.platform", "1");
    }

    private AdminPrincipal principal(
            long id,
            Long tenantId,
            String loginName,
            String role
    ) {
        return new AdminPrincipal(id, tenantId, loginName, null, role, true);
    }

    private void assertCode(Runnable action, String code) {
        BusinessException exception = catchBusiness(action);
        assertThat(exception.code()).isEqualTo(code);
    }

    private BusinessException catchBusiness(Runnable action) {
        BusinessException exception = catchThrowableOfType(
                BusinessException.class,
                action::run
        );
        assertThat(exception).isNotNull();
        return exception;
    }

    private void assertCounts(
            int documents,
            int versions,
            int jobs,
            int outboxEvents
    ) {
        assertThat(count("document")).isEqualTo(documents);
        assertThat(count("document_version")).isEqualTo(versions);
        assertThat(count("processing_job")).isEqualTo(jobs);
        assertThat(count("outbox_event")).isEqualTo(outboxEvents);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private long insertTenant(String name) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO tenant (name, status, created_at, updated_at)
                VALUES (?, '1', ?, ?)
                """,
                name,
                now,
                now
        );
        return value(
                "SELECT id FROM tenant WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                name
        );
    }

    private long insertAdmin(Long tenantId, String loginName, String role) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, ?, '{bcrypt}test-only-hash', ?, '1', ?, ?)
                """,
                tenantId,
                loginName,
                role,
                now,
                now
        );
        return value(
                "SELECT id FROM admin_user WHERE login_name = ?",
                Long.class,
                loginName
        );
    }

    private long insertKnowledgeBase(long tenantId, String name) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, ?, NULL, '1', ?, ?)
                """,
                tenantId,
                name,
                now,
                now
        );
        return value(
                """
                SELECT id FROM knowledge_base
                WHERE tenant_id = ? AND name = ?
                """,
                Long.class,
                tenantId,
                name
        );
    }
}
