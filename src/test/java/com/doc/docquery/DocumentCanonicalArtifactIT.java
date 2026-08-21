package com.doc.docquery;

import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.job.OrphanCanonicalArtifactReaper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentCanonicalService;
import com.doc.docquery.service.DocumentParseException;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.service.DocumentUploadCoordinator;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** MySQL + SeaweedFS 下验证 canonical 单对象、清单、幂等和安全回收。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.object-storage.enabled=true",
                "docquery.parsing.orphan-grace=0s",
                "docquery.parsing.orphan-scan-delay=1h",
                "docquery.messaging.infrastructure-enabled=false",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=false"
        }
)
class DocumentCanonicalArtifactIT {

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
    private DocumentCanonicalService canonicalService;
    @Autowired
    private SourceObjectStore sourceObjectStore;
    @Autowired
    private CanonicalArtifactStore canonicalArtifactStore;
    @Autowired
    private OrphanCanonicalArtifactReaper orphanReaper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM document_canonical_artifact");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
        deleteSourceObjects();
        deleteCanonicalObjects();
        tenantId = insertTenant();
        adminId = insertAdmin(tenantId);
        knowledgeBaseId = insertKnowledgeBase(tenantId);
    }

    @Test
    void fourFormatsCreateOneTraceableArtifactEachWithoutReadyTransition() throws Exception {
        DocumentUploadAcceptedVO pdf = upload(
                "PDF Manual",
                "pdf-1",
                "manual.pdf",
                pdfBytes()
        );
        DocumentUploadAcceptedVO docx = upload(
                "DOCX Manual",
                "docx-1",
                "manual.docx",
                docxBytes()
        );
        DocumentUploadAcceptedVO text = upload(
                "TXT Notes",
                "txt-1",
                "notes.txt",
                "first\n\nsecond".getBytes(StandardCharsets.UTF_8)
        );
        DocumentUploadAcceptedVO markdown = upload(
                "Markdown Guide",
                "md-1",
                "guide.md",
                "# Install\n\nRun it.\n".getBytes(StandardCharsets.UTF_8)
        );

        List<DocumentCanonicalArtifactEntity> artifacts = List.of(
                canonicalService.ensureCanonical(pdf.getDocumentVersionId()),
                canonicalService.ensureCanonical(docx.getDocumentVersionId()),
                canonicalService.ensureCanonical(text.getDocumentVersionId()),
                canonicalService.ensureCanonical(markdown.getDocumentVersionId())
        );

        assertThat(count("document_canonical_artifact")).isEqualTo(4);
        assertThat(canonicalArtifactStore.list("canonical/", 100)).hasSize(4);
        assertThat(sourceObjectStore.list("source/", 100)).hasSize(4);
        assertThat(artifacts)
                .allSatisfy(artifact -> {
                    assertThat(artifact.getCanonicalSha256()).hasSize(64);
                    assertThat(artifact.getCanonicalTextSha256()).hasSize(64);
                    assertThat(artifact.getBlockCount()).isPositive();
                    assertThat(artifact.getHeadingCount()).isPositive();
                });

        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                pdf.getDocumentVersionId()
        )).isEqualTo("1");
        assertThat(value(
                "SELECT status FROM processing_job WHERE document_version_id=?",
                String.class,
                pdf.getDocumentVersionId()
        )).isEqualTo("1");
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id=?",
                Long.class,
                pdf.getDocumentId()
        )).isNull();

        List<JsonNode> pdfRecords = readRecords(artifacts.get(0).getCanonicalObjectKey());
        assertThat(pdfRecords.get(0).get("recordType").asText()).isEqualTo("header");
        assertThat(pdfRecords.get(pdfRecords.size() - 1).get("complete").asBoolean())
                .isTrue();
        assertThat(pdfRecords.stream()
                .filter(record -> "heading".equals(record.get("recordType").asText()))
                .map(record -> record.get("title").asText()))
                .contains("PDF Manual", "Installation", "Verification");
        assertThat(pdfRecords.stream()
                .filter(record -> "block".equals(record.get("recordType").asText()))
                .map(record -> record.get("sourcePosition").get("pageNumber").asInt()))
                .containsOnly(1);
    }

    @Test
    void concurrentAndSequentialReplayKeepOneManifestAndOneObject() throws Exception {
        DocumentUploadAcceptedVO upload = upload(
                "Concurrent Guide",
                "concurrent-1",
                "guide.md",
                "# Start\n\ncontent\n".getBytes(StandardCharsets.UTF_8)
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<DocumentCanonicalArtifactEntity>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return canonicalService.ensureCanonical(upload.getDocumentVersionId());
                }));
            }
            start.countDown();
            DocumentCanonicalArtifactEntity first = futures.get(0).get();
            DocumentCanonicalArtifactEntity second = futures.get(1).get();
            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(canonicalService.ensureCanonical(upload.getDocumentVersionId()).getId())
                    .isEqualTo(first.getId());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("document_canonical_artifact")).isOne();
        assertThat(canonicalArtifactStore.list("canonical/", 100)).hasSize(1);
    }

    @Test
    void deterministicFailuresLeaveNoCanonicalFactOrObject() throws Exception {
        DocumentUploadAcceptedVO invalidText = upload(
                "Invalid Text",
                "invalid-text",
                "invalid.txt",
                new byte[]{(byte) 0xC3, (byte) 0x28}
        );
        DocumentParseException encoding = catchThrowableOfType(
                DocumentParseException.class,
                () -> canonicalService.ensureCanonical(invalidText.getDocumentVersionId())
        );
        assertThat(encoding.code()).isEqualTo("TEXT_ENCODING_UNSUPPORTED");
        assertThat(encoding.retryable()).isFalse();

        DocumentUploadAcceptedVO imagePdf = upload(
                "Image PDF",
                "image-pdf",
                "image.pdf",
                imageOnlyPdfBytes()
        );
        DocumentParseException noText = catchThrowableOfType(
                DocumentParseException.class,
                () -> canonicalService.ensureCanonical(imagePdf.getDocumentVersionId())
        );
        assertThat(noText.code()).isEqualTo("PDF_NO_EXTRACTABLE_TEXT");
        assertThat(count("document_canonical_artifact")).isZero();
        assertThat(canonicalArtifactStore.list("canonical/", 100)).isEmpty();
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                invalidText.getDocumentVersionId()
        )).isEqualTo("1");
    }

    @Test
    void orphanReaperDeletesOnlyUnreferencedCanonicalObjects() throws InterruptedException {
        DocumentUploadAcceptedVO upload = upload(
                "Referenced Guide",
                "referenced-1",
                "guide.md",
                "# Start\n\ncontent\n".getBytes(StandardCharsets.UTF_8)
        );
        DocumentCanonicalArtifactEntity referenced = canonicalService.ensureCanonical(
                upload.getDocumentVersionId()
        );
        String orphanKey = "canonical/" + tenantId + "/999/v1/orphan.jsonl";
        byte[] orphanBytes = "{\"recordType\":\"orphan\"}\n"
                .getBytes(StandardCharsets.UTF_8);
        canonicalArtifactStore.put(
                orphanKey,
                new ByteArrayInputStream(orphanBytes),
                orphanBytes.length,
                1_024
        );

        awaitCanonicalObjectEligibleForOrphanCleanup(orphanKey);
        orphanReaper.runOnce();

        assertThat(canonicalArtifactStore.exists(referenced.getCanonicalObjectKey())).isTrue();
        assertThat(canonicalArtifactStore.exists(orphanKey)).isFalse();
    }

    private void awaitCanonicalObjectEligibleForOrphanCleanup(String objectKey)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            CanonicalArtifactStore.ObjectSummary summary = canonicalArtifactStore.list(
                            "canonical/",
                            1_000
                    ).stream()
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
        throw new AssertionError("Canonical orphan did not enter cleanup eligibility window");
    }

    private List<JsonNode> readRecords(String objectKey) throws IOException {
        try (InputStream input = canonicalArtifactStore.open(objectKey)) {
            String jsonl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<JsonNode> records = new ArrayList<>();
            for (String line : jsonl.split("\n")) {
                if (!line.isBlank()) {
                    records.add(objectMapper.readTree(line));
                }
            }
            return records;
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
                        "application/octet-stream",
                        content
                )
        );
    }

    private AdminPrincipal tenantAdmin() {
        return new AdminPrincipal(adminId, tenantId, "parse.admin", null, "2", true);
    }

    private byte[] pdfBytes() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writePdfLine(content, "PDF Manual", 20, true, 740);
                writePdfLine(content, "Installation", 16, true, 650);
                writePdfLine(content, "Install the service.", 11, false, 625);
                writePdfLine(content, "Verification", 16, true, 550);
                writePdfLine(content, "Check the health endpoint.", 11, false, 525);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] imageOnlyPdfBytes() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docxBytes() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Service Manual");
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Startup");
            document.createParagraph().createRun().setText("Start the application.");
            document.write(output);
            return output.toByteArray();
        }
    }

    private void writePdfLine(
            PDPageContentStream content,
            String text,
            float size,
            boolean bold,
            float y
    ) throws IOException {
        content.beginText();
        content.setFont(
                new PDType1Font(
                        bold
                                ? Standard14Fonts.FontName.HELVETICA_BOLD
                                : Standard14Fonts.FontName.HELVETICA
                ),
                size
        );
        content.newLineAtOffset(72, y);
        content.showText(text);
        content.endText();
    }

    private long insertTenant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO tenant (name, status, created_at, updated_at) VALUES ('Parse Tenant', '1', ?, ?)",
                now,
                now
        );
        return value("SELECT id FROM tenant WHERE name='Parse Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, 'parse.admin', '{bcrypt}test', '2', '1', ?, ?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value("SELECT id FROM admin_user WHERE login_name='parse.admin'", Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, 'Parse KB', NULL, '1', ?, ?)
                """,
                ownerTenantId,
                now,
                now
        );
        return value(
                "SELECT id FROM knowledge_base WHERE tenant_id=? AND name='Parse KB'",
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

    private void deleteSourceObjects() {
        for (SourceObjectStore.ObjectSummary object
                : sourceObjectStore.list("source/", 1000)) {
            sourceObjectStore.delete(object.objectKey());
        }
    }

    private void deleteCanonicalObjects() {
        for (CanonicalArtifactStore.ObjectSummary object
                : canonicalArtifactStore.list("canonical/", 1000)) {
            canonicalArtifactStore.delete(object.objectKey());
        }
    }
}
