package com.doc.docquery;

import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.job.OrphanSourceObjectReaper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadCoordinator;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MySQL + SeaweedFS 下验证真实 multipart 上传、摘要和孤立对象清理。 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "docquery.object-storage.enabled=true",
                "docquery.object-storage.upload-max-bytes=1048576",
                "docquery.object-storage.orphan-grace=0s",
                "docquery.object-storage.orphan-scan-delay=1h",
                "docquery.messaging.infrastructure-enabled=false",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=false"
        }
)
class DocumentUploadObjectStorageIT {

    private static final Logger LOG = LoggerFactory.getLogger(
            DocumentUploadObjectStorageIT.class
    );
    private static final long FIFTY_MIB = 50L * 1024L * 1024L;
    private static final String ACCESS_KEY = "docquery-test";
    private static final String SECRET_KEY = "docquery-test-secret";
    private static final String BUCKET = "docquery-source";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password");

    @Container
    static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
            "chrislusf/seaweedfs:4.40"
    )
            .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
            .withEnv("S3_BUCKET", BUCKET)
            .withExposedPorts(8333)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void objectStorageProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "docquery.object-storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333)
        );
        registry.add("docquery.object-storage.access-key", () -> ACCESS_KEY);
        registry.add("docquery.object-storage.secret-key", () -> SECRET_KEY);
        registry.add("docquery.object-storage.bucket", () -> BUCKET);
    }

    @Autowired
    private DocumentUploadCoordinator uploadCoordinator;
    @Autowired
    private SourceObjectStore objectStore;
    @Autowired
    private OrphanSourceObjectReaper orphanReaper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;

    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
        for (SourceObjectStore.ObjectSummary object : objectStore.list("source/", 1000)) {
            objectStore.delete(object.objectKey());
        }
        tenantId = insertTenant();
        adminId = insertAdmin(tenantId);
        knowledgeBaseId = insertKnowledgeBase(tenantId);
    }

    @Test
    void multipartEndpointStoresExactBytesAndCreatesAcceptedFacts() throws Exception {
        byte[] bytes = "SeaweedFS真实上传内容".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"documentName\":\"维修手册\"}".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manual.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                bytes
        );
        AdminPrincipal principal = tenantAdmin();
        var authenticationToken = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities()
        );

        String body = mockMvc.perform(multipart(
                                "/api/admin/v1/tenants/{tenantId}/knowledge-bases/{kbId}/documents",
                                tenantId,
                                knowledgeBaseId
                        )
                        .file(metadata)
                        .file(file)
                        .header("Idempotency-Key", "http-upload-1")
                        .with(authentication(authenticationToken))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.versionStatus").value("1"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long versionId = JsonPath.<Number>read(body, "$.documentVersionId").longValue();
        String objectKey = value(
                "SELECT source_object_key FROM document_version WHERE id = ?",
                String.class,
                versionId
        );
        assertThat(objectKey)
                .startsWith("source/" + tenantId + "/")
                .doesNotContain("manual.pdf");
        assertThat(value(
                "SELECT source_size_bytes FROM document_version WHERE id = ?",
                Long.class,
                versionId
        )).isEqualTo(bytes.length);
        try (InputStream stored = objectStore.open(objectKey)) {
            assertThat(stored.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void allSupportedFormatsWorkAndIdempotentReplayRemovesExtraObject() {
        String[] filenames = {"a.pdf", "b.docx", "c.txt", "d.markdown"};
        for (int index = 0; index < filenames.length; index++) {
            upload(
                    "Document " + index,
                    "supported-" + index,
                    filenames[index],
                    ("content-" + index).getBytes(StandardCharsets.UTF_8)
            );
        }
        assertThat(objectStore.list("source/", 100).size()).isEqualTo(4);

        DocumentUploadAcceptedVO original = upload(
                "Replay",
                "replay-key",
                "replay.md",
                "same".getBytes(StandardCharsets.UTF_8)
        );
        DocumentUploadAcceptedVO replay = upload(
                "Replay",
                "replay-key",
                "replay.md",
                "same".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(replay.getDocumentVersionId()).isEqualTo(original.getDocumentVersionId());
        assertThat(objectStore.list("source/", 100).size()).isEqualTo(5);
    }

    @Test
    void invalidAndOversizedFilesLeaveNoObjectOrDatabaseFact() {
        BusinessException unsupported = catchThrowableOfType(
                BusinessException.class,
                () -> upload("Bad", "bad-format", "bad.zip", new byte[]{1})
        );
        assertThat(unsupported.code()).isEqualTo("UNSUPPORTED_DOCUMENT_FORMAT");

        byte[] oversized = new byte[1_048_577];
        BusinessException tooLarge = catchThrowableOfType(
                BusinessException.class,
                () -> upload("Large", "too-large", "large.pdf", oversized)
        );
        assertThat(tooLarge.code()).isEqualTo("FILE_TOO_LARGE");
        assertThat(objectStore.list("source/", 100)).isEmpty();
        assertThat(count("document")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void orphanReaperDeletesOnlyUnreferencedObjects() throws InterruptedException {
        DocumentUploadAcceptedVO accepted = upload(
                "Referenced",
                "referenced",
                "referenced.txt",
                "kept".getBytes(StandardCharsets.UTF_8)
        );
        String referencedKey = value(
                "SELECT source_object_key FROM document_version WHERE id = ?",
                String.class,
                accepted.getDocumentVersionId()
        );
        String orphanKey = "source/" + tenantId + "/2000/01/orphan-test";
        byte[] orphan = "orphan".getBytes(StandardCharsets.UTF_8);
        objectStore.put(
                orphanKey,
                new java.io.ByteArrayInputStream(orphan),
                orphan.length,
                1_048_576,
                "text/plain"
        );

        awaitSourceObjectEligibleForOrphanCleanup(orphanKey);
        orphanReaper.runOnce();

        assertThat(objectStore.exists(referencedKey)).isTrue();
        assertThat(objectStore.exists(orphanKey)).isFalse();
    }

    private void awaitSourceObjectEligibleForOrphanCleanup(String objectKey)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            SourceObjectStore.ObjectSummary summary = objectStore.list("source/", 1_000)
                    .stream()
                    .filter(candidate -> candidate.objectKey().equals(objectKey))
                    .findFirst()
                    .orElse(null);
            if (summary != null
                    && summary.lastModified() != null
                    && summary.lastModified().isBefore(Instant.now())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Source orphan did not enter cleanup eligibility window");
    }

    @Test
    void recordsStreamingSamplesAtOneTenAndFiftyMib() {
        for (int sizeMiB : new int[]{1, 10, 50}) {
            byte[] content = new byte[sizeMiB * 1024 * 1024];
            java.util.Arrays.fill(content, (byte) sizeMiB);
            String objectKey = "source/performance/" + sizeMiB + "-mib";

            // 输入样本已在计时前分配，heapDelta 主要观察上传链路的额外堆占用。
            long heapBefore = usedHeapBytes();
            long started = System.nanoTime();
            SourceObjectStore.WriteResult result = objectStore.put(
                    objectKey,
                    new ByteArrayInputStream(content),
                    content.length,
                    FIFTY_MIB,
                    MediaType.APPLICATION_OCTET_STREAM_VALUE
            );
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
            );
            long heapDelta = usedHeapBytes() - heapBefore;

            LOG.info(
                    "N2.2 S3 sample: sizeMiB={}, elapsedMs={}, heapDeltaBytes={}",
                    sizeMiB,
                    elapsedMillis,
                    heapDelta
            );
            assertThat(result.sizeBytes()).isEqualTo(content.length);
            assertThat(result.sha256()).isEqualTo(sha256(content));
            objectStore.delete(objectKey);
        }
    }

    private DocumentUploadAcceptedVO upload(
            String documentName,
            String idempotencyKey,
            String filename,
            byte[] content
    ) {
        CreateDocumentUploadMetadataDTO metadata = new CreateDocumentUploadMetadataDTO();
        metadata.setDocumentName(documentName);
        return uploadCoordinator.uploadNewDocument(
                tenantAdmin(),
                tenantId,
                knowledgeBaseId,
                idempotencyKey,
                metadata,
                new MockMultipartFile(
                        "file",
                        filename,
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        content
                )
        );
    }

    private AdminPrincipal tenantAdmin() {
        return new AdminPrincipal(
                adminId,
                tenantId,
                "upload.admin",
                null,
                "2",
                true
        );
    }

    private long insertTenant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO tenant (name, status, created_at, updated_at) VALUES ('Upload Tenant', '1', ?, ?)",
                now,
                now
        );
        return value("SELECT id FROM tenant WHERE name = 'Upload Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, 'upload.admin', '{bcrypt}test', '2', '1', ?, ?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value("SELECT id FROM admin_user WHERE login_name='upload.admin'", Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, 'Upload KB', NULL, '1', ?, ?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value(
                "SELECT id FROM knowledge_base WHERE tenant_id=? AND name='Upload KB'",
                Long.class,
                ownerTenantId
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
