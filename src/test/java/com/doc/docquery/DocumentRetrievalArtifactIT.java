package com.doc.docquery;

import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import com.doc.docquery.job.OrphanRetrievalArtifactReaper;
import com.doc.docquery.retrieval.DocumentProfileSemantic;
import com.doc.docquery.retrieval.CanonicalArtifactReader;
import com.doc.docquery.retrieval.RetrievalArtifactGenerator;
import com.doc.docquery.retrieval.RetrievalArtifactValidator;
import com.doc.docquery.retrieval.RetrievalGenerationFingerprint;
import com.doc.docquery.retrieval.RetrievalJsonlReader;
import com.doc.docquery.retrieval.RetrievalJsonlWriter;
import com.doc.docquery.retrieval.EmbeddingCodec;
import com.doc.docquery.retrieval.NavigationTextBuilder;
import com.doc.docquery.retrieval.RetrievalSemanticValidator;
import com.doc.docquery.retrieval.RetrievalNodeSemantic;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentCanonicalService;
import com.doc.docquery.service.DocumentRetrievalService;
import com.doc.docquery.service.DocumentUploadCoordinator;
import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalArtifactStore;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.service.impl.DocumentRetrievalServiceImpl;
import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentRetrievalArtifactMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** MySQL + SeaweedFS + Fake Gateway 下验证 N2.4 完整持久化和幂等边界。 */
@Testcontainers
@Import(DocumentRetrievalArtifactIT.FakeGatewayConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.object-storage.enabled=true",
                "docquery.retrieval.provider-enabled=false",
                "docquery.retrieval.orphan-grace=0s",
                "docquery.retrieval.orphan-scan-delay=1h",
                "docquery.messaging.infrastructure-enabled=false",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=false"
        }
)
class DocumentRetrievalArtifactIT {

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
        registry.add("docquery.object-storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":"
                        + SEAWEEDFS.getMappedPort(8333));
        registry.add("docquery.object-storage.access-key", () -> ACCESS_KEY);
        registry.add("docquery.object-storage.secret-key", () -> SECRET_KEY);
        registry.add("docquery.object-storage.bucket", () -> BUCKET);
    }

    @Autowired
    private DocumentUploadCoordinator uploadCoordinator;
    @Autowired
    private DocumentCanonicalService canonicalService;
    @Autowired
    private DocumentRetrievalService retrievalService;
    @Autowired
    private SourceObjectStore sourceStore;
    @Autowired
    private CanonicalArtifactStore canonicalStore;
    @Autowired
    private RetrievalArtifactStore retrievalStore;
    @Autowired
    private OrphanRetrievalArtifactReaper orphanReaper;
    @Autowired
    private FakeChatGateway chatGateway;
    @Autowired
    private FakeEmbeddingGateway embeddingGateway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM document_retrieval_artifact");
        jdbcTemplate.update("DELETE FROM document_canonical_artifact");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
        deleteObjects();
        chatGateway.calls.set(0);
        embeddingGateway.calls.set(0);
        embeddingGateway.invalidDimension = false;
        tenantId = insertTenant();
        adminId = insertAdmin(tenantId);
        knowledgeBaseId = insertKnowledgeBase(tenantId);
    }

    @Test
    void createsOneValidatedObjectWithoutChangingProcessingLifecycle() throws Exception {
        DocumentUploadAcceptedVO upload = upload("Guide", "retrieval-1", "guide.md",
                "# Install\n\nRun it.\n## Verify\n\nCheck health.\n"
                        .getBytes(StandardCharsets.UTF_8));
        canonicalService.ensureCanonical(upload.getDocumentVersionId());

        DocumentRetrievalArtifactEntity artifact = retrievalService.ensureRetrieval(
                upload.getDocumentVersionId()
        );

        assertThat(artifact.getProfileCount()).isOne();
        assertThat(artifact.getNodeCount()).isEqualTo(2);
        assertThat(artifact.getVectorCount()).isEqualTo(3);
        assertThat(artifact.getEmbeddingDimension()).isEqualTo(2560);
        assertThat(artifact.getChatModel()).isEqualTo("deepseek-v4-flash");
        assertThat(artifact.getRetrievalSha256()).hasSize(64);
        assertThat(retrievalStore.list("retrieval/", 100)).hasSize(1);

        List<JsonNode> records = readRecords(artifact.getRetrievalObjectKey());
        assertThat(records.get(0).get("recordType").asText()).isEqualTo("header");
        assertThat(records.get(0).get("thinkingMode").asText()).isEqualTo("DISABLED");
        assertThat(records.stream().filter(record -> "profile".equals(
                record.get("recordType").asText()))).hasSize(1);
        assertThat(records.stream().filter(record -> "node".equals(
                record.get("recordType").asText()))).hasSize(2);
        JsonNode embedding = records.get(1).get("embedding");
        byte[] vector = Base64.getDecoder().decode(embedding.get("value").asText());
        assertThat(vector).hasSize(2560 * Float.BYTES);
        assertThat(ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN).getFloat())
                .isEqualTo(1.0f);
        assertThat(records.get(records.size() - 1).get("complete").asBoolean()).isTrue();

        assertThat(value("SELECT status FROM document_version WHERE id=?", String.class,
                upload.getDocumentVersionId())).isEqualTo("1");
        assertThat(value("SELECT status FROM processing_job WHERE document_version_id=?",
                String.class, upload.getDocumentVersionId())).isEqualTo("1");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                upload.getDocumentId())).isNull();
    }

    @Test
    void sequentialReplaySkipsGatewaysAndConcurrentReplayKeepsOneObject() throws Exception {
        DocumentUploadAcceptedVO upload = upload("Replay", "retrieval-2", "guide.md",
                "# Start\n\ncontent\n".getBytes(StandardCharsets.UTF_8));
        canonicalService.ensureCanonical(upload.getDocumentVersionId());
        DocumentRetrievalArtifactEntity first = retrievalService.ensureRetrieval(
                upload.getDocumentVersionId());
        int chatCalls = chatGateway.calls.get();
        int embeddingCalls = embeddingGateway.calls.get();

        DocumentRetrievalArtifactEntity replay = retrievalService.ensureRetrieval(
                upload.getDocumentVersionId());
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(chatGateway.calls).hasValue(chatCalls);
        assertThat(embeddingGateway.calls).hasValue(embeddingCalls);

        // 另一个版本从空清单开始并发，数据库唯一约束选出同一胜者。
        DocumentUploadAcceptedVO concurrent = upload("Concurrent", "retrieval-3", "c.md",
                "# Start\n\ncontent\n".getBytes(StandardCharsets.UTF_8));
        canonicalService.ensureCanonical(concurrent.getDocumentVersionId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<DocumentRetrievalArtifactEntity>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return retrievalService.ensureRetrieval(concurrent.getDocumentVersionId());
                }));
            }
            start.countDown();
            assertThat(futures.get(0).get().getId()).isEqualTo(futures.get(1).get().getId());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("document_retrieval_artifact")).isEqualTo(2);
        assertThat(retrievalStore.list("retrieval/", 100)).hasSize(2);
    }

    @Test
    void invalidEmbeddingLeavesNoManifestOrObject() {
        DocumentUploadAcceptedVO upload = upload("Bad Vector", "retrieval-4", "bad.txt",
                "plain text".getBytes(StandardCharsets.UTF_8));
        canonicalService.ensureCanonical(upload.getDocumentVersionId());
        embeddingGateway.invalidDimension = true;

        RetrievalGenerationException failure = catchThrowableOfType(
                RetrievalGenerationException.class,
                () -> retrievalService.ensureRetrieval(upload.getDocumentVersionId())
        );

        assertThat(failure.code()).isEqualTo("EMBEDDING_RESPONSE_INVALID");
        assertThat(count("document_retrieval_artifact")).isZero();
        assertThat(retrievalStore.list("retrieval/", 100)).isEmpty();
    }

    @Test
    void orphanReaperRetainsReferencedObjectAndDeletesOnlyUnreferencedObject()
            throws InterruptedException {
        DocumentUploadAcceptedVO upload = upload("Referenced", "retrieval-5", "r.txt",
                "plain text".getBytes(StandardCharsets.UTF_8));
        canonicalService.ensureCanonical(upload.getDocumentVersionId());
        DocumentRetrievalArtifactEntity referenced = retrievalService.ensureRetrieval(
                upload.getDocumentVersionId());
        String orphanKey = "retrieval/" + tenantId + "/999/v1/orphan.jsonl";
        byte[] bytes = "{\"recordType\":\"orphan\"}\n".getBytes(StandardCharsets.UTF_8);
        retrievalStore.put(orphanKey, new ByteArrayInputStream(bytes), bytes.length, 1024);

        awaitRetrievalObjectEligibleForOrphanCleanup(orphanKey);
        orphanReaper.runOnce();

        assertThat(retrievalStore.exists(referenced.getRetrievalObjectKey())).isTrue();
        assertThat(retrievalStore.exists(orphanKey)).isFalse();
    }

    private void awaitRetrievalObjectEligibleForOrphanCleanup(String objectKey)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            RetrievalArtifactStore.ObjectSummary summary = retrievalStore.list(
                            "retrieval/",
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
        throw new AssertionError("Retrieval orphan did not enter cleanup eligibility window");
    }

    private List<JsonNode> readRecords(String key) throws IOException {
        try (InputStream input = retrievalStore.open(key)) {
            List<JsonNode> records = new ArrayList<>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .split("\n")) {
                if (!line.isBlank()) {
                    records.add(objectMapper.readTree(line));
                }
            }
            return records;
        }
    }

    private DocumentUploadAcceptedVO upload(
            String name,
            String idempotencyKey,
            String filename,
            byte[] content
    ) {
        CreateDocumentUploadMetadataDTO metadata = new CreateDocumentUploadMetadataDTO();
        metadata.setDocumentName(name);
        return uploadCoordinator.uploadNewDocument(
                new AdminPrincipal(adminId, tenantId, "retrieval.admin", null, "2", true),
                tenantId,
                knowledgeBaseId,
                idempotencyKey,
                metadata,
                new MockMultipartFile("file", filename, "application/octet-stream", content)
        );
    }

    private long insertTenant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO tenant (name,status,created_at,updated_at) "
                + "VALUES ('Retrieval Tenant','1',?,?)", now, now);
        return value("SELECT id FROM tenant WHERE name='Retrieval Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, 'retrieval.admin', '{bcrypt}test', '2', '1', ?, ?)
                """, ownerTenantId, now, now);
        return value("SELECT id FROM admin_user WHERE login_name='retrieval.admin'", Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, 'Retrieval KB', NULL, '1', ?, ?)
                """, ownerTenantId, now, now);
        return value("SELECT id FROM knowledge_base WHERE tenant_id=?", Long.class,
                ownerTenantId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private void deleteObjects() {
        for (RetrievalArtifactStore.ObjectSummary object : retrievalStore.list("retrieval/", 1000)) {
            retrievalStore.delete(object.objectKey());
        }
        for (CanonicalArtifactStore.ObjectSummary object : canonicalStore.list("canonical/", 1000)) {
            canonicalStore.delete(object.objectKey());
        }
        for (SourceObjectStore.ObjectSummary object : sourceStore.list("source/", 1000)) {
            sourceStore.delete(object.objectKey());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGatewayConfig {
        @Bean
        FakeChatGateway fakeChatGateway() {
            return new FakeChatGateway();
        }

        @Bean
        FakeEmbeddingGateway fakeEmbeddingGateway() {
            return new FakeEmbeddingGateway();
        }

        @Bean
        RetrievalArtifactGenerator retrievalArtifactGenerator(
                FakeChatGateway chat,
                FakeEmbeddingGateway embedding,
                RetrievalSemanticValidator semanticValidator,
                NavigationTextBuilder textBuilder,
                EmbeddingCodec codec,
                RetrievalGenerationFingerprint fingerprint,
                DocumentRetrievalProperties properties
        ) {
            return new RetrievalArtifactGenerator(
                    chat, embedding, semanticValidator, textBuilder, codec,
                    fingerprint, properties
            );
        }

        /** 组件条件早于测试 Bean 解析，因此测试显式组装同一个生产 Service。 */
        @Bean
        DocumentRetrievalService documentRetrievalService(
                DocumentVersionMapper versionMapper,
                DocumentMapper documentMapper,
                DocumentCanonicalArtifactMapper canonicalMapper,
                DocumentRetrievalArtifactMapper retrievalMapper,
                CanonicalArtifactStore canonicalStore,
                RetrievalArtifactStore retrievalStore,
                CanonicalArtifactReader canonicalReader,
                RetrievalArtifactGenerator generator,
                RetrievalArtifactValidator validator,
                RetrievalJsonlWriter writer,
                RetrievalJsonlReader reader,
                RetrievalGenerationFingerprint fingerprint,
                DocumentRetrievalProperties properties
        ) {
            return new DocumentRetrievalServiceImpl(
                    versionMapper, documentMapper, canonicalMapper, retrievalMapper,
                    canonicalStore, retrievalStore, canonicalReader, generator, validator,
                    writer, reader, fingerprint, properties
            );
        }
    }

    static final class FakeChatGateway implements RetrievalCardChatGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<GeneratedNode> generateNodes(List<NodeInput> inputs, String correctionHint) {
            calls.incrementAndGet();
            return inputs.stream().map(input -> new GeneratedNode(
                    input.requestId(),
                    new RetrievalNodeSemantic("Summary " + input.titlePath(),
                            List.of("topic"), List.of(), List.of("What is covered?"))
            )).toList();
        }

        @Override
        public DocumentProfileSemantic generateProfile(
                ProfileInput input,
                String correctionHint
        ) {
            calls.incrementAndGet();
            return new DocumentProfileSemantic("Document purpose", List.of("topic"),
                    List.of(), List.of("What is this document about?"));
        }
    }

    static final class FakeEmbeddingGateway implements NavigationEmbeddingGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean invalidDimension;

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            calls.incrementAndGet();
            int dimension = invalidDimension ? 2 : 2560;
            return texts.stream().map(text -> {
                float[] vector = new float[dimension];
                for (int index = 0; index < dimension; index++) {
                    vector[index] = index + 1.0f;
                }
                return vector;
            }).toList();
        }
    }
}
