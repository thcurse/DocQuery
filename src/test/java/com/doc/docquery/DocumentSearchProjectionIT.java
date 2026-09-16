package com.doc.docquery;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.config.ChatProfilesProperties;
import com.doc.docquery.config.MessagingProperties;
import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import com.doc.docquery.entity.DocumentSearchProjectionEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.parser.BlockKind;
import com.doc.docquery.parser.CanonicalDocumentAssembler;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.CanonicalJsonlWriter;
import com.doc.docquery.parser.DeepDocDocumentParser;
import com.doc.docquery.parser.DocumentFormatParser;
import com.doc.docquery.parser.ParsedDocument;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentRetrievalArtifactMapper;
import com.doc.docquery.mapper.DocumentSearchProjectionMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.mapper.OutboxEventMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.messaging.DocumentProcessingMessage;
import com.doc.docquery.messaging.ConfirmedRabbitPublisher;
import com.doc.docquery.messaging.RabbitMqTopology;
import com.doc.docquery.messaging.DocumentDeletionRabbitMqTopology;
import com.doc.docquery.job.OutboxPublisher;
import com.doc.docquery.retrieval.CanonicalArtifactReader;
import com.doc.docquery.retrieval.DocumentProfileSemantic;
import com.doc.docquery.retrieval.EmbeddingCodec;
import com.doc.docquery.retrieval.NavigationTextBuilder;
import com.doc.docquery.retrieval.NavigationPartitionPlanner;
import com.doc.docquery.retrieval.RetrievalArtifactGenerator;
import com.doc.docquery.retrieval.RetrievalArtifactValidator;
import com.doc.docquery.retrieval.RetrievalGenerationFingerprint;
import com.doc.docquery.retrieval.RetrievalJsonlReader;
import com.doc.docquery.retrieval.RetrievalJsonlWriter;
import com.doc.docquery.retrieval.RetrievalNodeSemantic;
import com.doc.docquery.retrieval.RetrievalSemanticValidator;
import com.doc.docquery.search.SearchProjectionFingerprint;
import com.doc.docquery.search.SearchProjectionStore;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentCanonicalService;
import com.doc.docquery.service.DocumentIngestionProcessor;
import com.doc.docquery.service.DocumentManagementService;
import com.doc.docquery.service.DocumentDeletionService;
import com.doc.docquery.service.DocumentProcessingException;
import com.doc.docquery.service.DocumentRetrievalService;
import com.doc.docquery.service.DocumentSearchProjectionService;
import com.doc.docquery.service.DocumentUploadCoordinator;
import com.doc.docquery.service.DocumentVersionActivationService;
import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalArtifactStore;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.service.impl.DocumentIngestionProcessorImpl;
import com.doc.docquery.service.impl.DocumentCanonicalServiceImpl;
import com.doc.docquery.service.impl.DocumentRetrievalServiceImpl;
import com.doc.docquery.service.impl.DocumentSearchProjectionServiceImpl;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** MySQL、SeaweedFS、RabbitMQ、Elasticsearch 和假模型下验收入库与删除完整边界。 */
@Testcontainers
@Import(DocumentSearchProjectionIT.FakePipelineConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.object-storage.enabled=true",
                "docquery.retrieval.provider-enabled=false",
                "docquery.search.enabled=true",
                "docquery.messaging.infrastructure-enabled=true",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=true",
                "docquery.messaging.deletion-listener-enabled=true",
                "docquery.messaging.job-lease=30s",
                "docquery.messaging.job-heartbeat=2s",
                "docquery.messaging.confirm-timeout=5s"
        }
)
class DocumentSearchProjectionIT {

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

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:9.4.4"
    )
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            "rabbitmq:4.3.4-management"
    );

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("docquery.object-storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":"
                        + SEAWEEDFS.getMappedPort(8333));
        registry.add("docquery.object-storage.access-key", () -> ACCESS_KEY);
        registry.add("docquery.object-storage.secret-key", () -> SECRET_KEY);
        registry.add("docquery.object-storage.bucket", () -> BUCKET);
        // Testcontainers 返回 host:port，而生产配置要求携带协议的完整 Endpoint。
        registry.add("docquery.search.endpoint",
                () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    @Autowired
    private DocumentUploadCoordinator uploadCoordinator;
    @Autowired
    private DocumentManagementService managementService;
    @Autowired
    private DocumentDeletionService deletionService;
    @Autowired
    private DocumentCanonicalService canonicalService;
    @Autowired
    private DocumentRetrievalService retrievalService;
    @Autowired
    private DocumentSearchProjectionService projectionService;
    @Autowired
    private DocumentVersionActivationService activationService;
    @Autowired
    private SourceObjectStore sourceStore;
    @Autowired
    private CanonicalArtifactStore canonicalStore;
    @Autowired
    private RetrievalArtifactStore retrievalStore;
    @Autowired
    private ElasticsearchClient elasticsearch;
    @Autowired
    private ConfirmedRabbitPublisher rabbitPublisher;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private DocumentVersionMapper versionMapper;
    @Autowired
    private DocumentCanonicalArtifactMapper canonicalArtifactMapper;
    @Autowired
    private DocumentRetrievalArtifactMapper retrievalArtifactMapper;
    @Autowired
    private DocumentSearchProjectionMapper projectionMapper;
    @Autowired
    private ProcessingJobMapper jobMapper;
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private MessagingProperties messagingProperties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long knowledgeBaseId;
    private long adminId;

    @BeforeEach
    void resetState() {
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(RabbitMqTopology.MAIN_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMqTopology.RETRY_QUEUE_1, false);
        rabbitAdmin.purgeQueue(RabbitMqTopology.RETRY_QUEUE_2, false);
        rabbitAdmin.purgeQueue(RabbitMqTopology.RETRY_QUEUE_3, false);
        rabbitAdmin.purgeQueue(RabbitMqTopology.DLQ, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.MAIN_QUEUE, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.RETRY_QUEUE_1, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.RETRY_QUEUE_2, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.RETRY_QUEUE_3, false);
        rabbitAdmin.purgeQueue(DocumentDeletionRabbitMqTopology.DLQ, false);
        jdbcTemplate.update("DELETE FROM document_search_projection");
        jdbcTemplate.update("DELETE FROM document_retrieval_artifact");
        jdbcTemplate.update("DELETE FROM document_canonical_artifact");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM document_deletion_job");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
        deleteObjects();
        tenantId = insertTenant();
        adminId = insertAdmin(tenantId);
        knowledgeBaseId = insertKnowledgeBase(tenantId);
    }

    @Test
    void rabbitMessageCompletesArtifactsProjectionAndActivation() throws Exception {
        DocumentUploadAcceptedVO upload = upload(
                "Listener Guide",
                "search-listener",
                "listener.md",
                "# Pipeline\n\nProcess the document end to end.\n"
                        .getBytes(StandardCharsets.UTF_8)
        );
        long jobId = upload.getProcessingJobId();
        long eventId = value(
                "SELECT id FROM outbox_event WHERE processing_job_id=?",
                Long.class,
                jobId
        );
        DocumentProcessingMessage message = new DocumentProcessingMessage(
                1,
                eventId,
                "1",
                tenantId,
                knowledgeBaseId,
                upload.getDocumentId(),
                upload.getDocumentVersionId(),
                jobId
        );

        boolean confirmed = rabbitPublisher.publish(
                RabbitMqTopology.MAIN_EXCHANGE,
                RabbitMqTopology.MAIN_ROUTING_KEY,
                objectMapper.writeValueAsBytes(message),
                Long.toString(eventId),
                Map.of(RabbitMqTopology.RETRY_HEADER, 0)
        );
        assertThat(confirmed).isTrue();
        awaitJobStatus(jobId, "3", 30_000);

        assertThat(count("document_canonical_artifact")).isOne();
        assertThat(count("document_retrieval_artifact")).isOne();
        assertThat(count("document_search_projection")).isOne();
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                upload.getDocumentVersionId()
        )).isEqualTo("2");
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id=?",
                Long.class,
                upload.getDocumentId()
        )).isEqualTo(upload.getDocumentVersionId());
        assertThat(elasticsearch.count(request -> request
                .index("docquery-evidence")
                .query(query -> query.term(term -> term
                        .field("document_version_id")
                        .value(upload.getDocumentVersionId())))).count()).isPositive();
        assertThat(elasticsearch.count(request -> request
                .index("docquery-navigation")
                .query(query -> query.term(term -> term
                        .field("document_version_id")
                        .value(upload.getDocumentVersionId())))).count()).isPositive();
    }

    @Test
    void failedVersionManualRetryPublishesNewAttemptAndBecomesReady() throws Exception {
        DocumentUploadAcceptedVO first = upload(
                "Retry Pipeline Guide",
                "retry-pipeline-v1",
                "retry-v1.md",
                "# Stable\n\nVersion one remains available.\n"
                        .getBytes(StandardCharsets.UTF_8)
        );
        publishPendingOutbox();
        awaitJobStatus(first.getProcessingJobId(), "3", 30_000);

        DocumentUploadAcceptedVO second = uploadCoordinator.uploadNewVersion(
                tenantAdmin(),
                tenantId,
                knowledgeBaseId,
                first.getDocumentId(),
                "retry-pipeline-v2",
                new MockMultipartFile(
                        "file",
                        "retry-v2.md",
                        "text/markdown",
                        "# Recovered\n\nVersion two succeeds after manual retry.\n"
                                .getBytes(StandardCharsets.UTF_8)
                )
        );
        LocalDateTime failedAt = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                UPDATE document_version
                SET status='3', failure_code='TEST_TRANSIENT',
                    failure_message='safe transient failure', failed_at=?, updated_at=?
                WHERE id=?
                """, failedAt, failedAt, second.getDocumentVersionId());
        jdbcTemplate.update("""
                UPDATE processing_job
                SET status='4', failure_code='TEST_TRANSIENT',
                    failure_message='safe transient failure', failure_retryable=1,
                    finished_at=?, updated_at=?
                WHERE id=?
                """, failedAt, failedAt, second.getProcessingJobId());
        jdbcTemplate.update("""
                UPDATE outbox_event SET status='3', sent_at=?, updated_at=?
                WHERE processing_job_id=?
                """, failedAt, failedAt, second.getProcessingJobId());

        var accepted = managementService.retryProcessingJob(
                tenantAdmin(),
                tenantId,
                second.getProcessingJobId(),
                "retry-pipeline-manual"
        );
        assertThat(accepted.getAttemptNo()).isEqualTo(2);
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id=?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(first.getDocumentVersionId());

        publishPendingOutbox();
        awaitJobStatus(accepted.getProcessingJobId(), "3", 30_000);

        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                second.getDocumentVersionId()
        )).isEqualTo("2");
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id=?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(second.getDocumentVersionId());
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                first.getDocumentVersionId()
        )).isEqualTo("2");
        assertThat(value(
                "SELECT COUNT(*) FROM processing_job WHERE document_version_id=?",
                Integer.class,
                second.getDocumentVersionId()
        )).isEqualTo(2);
    }

    @Test
    void rebuildRunsCompleteFakePipelineAndSwitchesOnlyAfterReady() throws Exception {
        byte[] content = "# Stable Source\n\nThe same original file is rebuilt safely.\n"
                .getBytes(StandardCharsets.UTF_8);
        DocumentUploadAcceptedVO first = upload(
                "Rebuild Pipeline Guide",
                "rebuild-pipeline-v1",
                "rebuild-pipeline.md",
                content
        );
        publishPendingOutbox();
        awaitJobStatus(first.getProcessingJobId(), "3", 30_000);
        String firstKey = value(
                "SELECT source_object_key FROM document_version WHERE id=?",
                String.class,
                first.getDocumentVersionId()
        );
        String firstSha = value(
                "SELECT source_sha256 FROM document_version WHERE id=?",
                String.class,
                first.getDocumentVersionId()
        );

        DocumentUploadAcceptedVO rebuilt = uploadCoordinator.rebuildDocument(
                tenantAdmin(),
                tenantId,
                knowledgeBaseId,
                first.getDocumentId(),
                "rebuild-pipeline-command"
        );

        assertThat(rebuilt.getVersionNo()).isEqualTo(2);
        assertThat(value(
                "SELECT active_version_id FROM document WHERE id=?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(first.getDocumentVersionId());
        assertThat(value(
                "SELECT source_sha256 FROM document_version WHERE id=?",
                String.class,
                rebuilt.getDocumentVersionId()
        )).isEqualTo(firstSha);
        assertThat(value(
                "SELECT source_object_key FROM document_version WHERE id=?",
                String.class,
                rebuilt.getDocumentVersionId()
        )).isNotEqualTo(firstKey);

        publishPendingOutbox();
        awaitJobStatus(rebuilt.getProcessingJobId(), "3", 30_000);

        assertThat(value(
                "SELECT active_version_id FROM document WHERE id=?",
                Long.class,
                first.getDocumentId()
        )).isEqualTo(rebuilt.getDocumentVersionId());
        assertThat(value(
                "SELECT status FROM document_version WHERE id=?",
                String.class,
                first.getDocumentVersionId()
        )).isEqualTo("2");
        assertThat(canonicalArtifactMapper.findByDocumentVersionId(
                rebuilt.getDocumentVersionId()
        )).isNotNull();
        assertThat(retrievalArtifactMapper.findByDocumentVersionId(
                rebuilt.getDocumentVersionId()
        ).getSchemaVersion()).isEqualTo(2);
        assertThat(projectionMapper.findByDocumentVersionId(
                rebuilt.getDocumentVersionId()
        )).isNotNull();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCQUERY_DOCX_PARSER_URL", matches = ".+")
    void rebuildCorrectsLegacyDocxTableHeadingThroughRealDeepDoc() throws Exception {
        byte[] content;
        try (XWPFDocument docx = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CTStyle headingStyle = CTStyle.Factory.newInstance();
            headingStyle.setStyleId("Heading1");
            headingStyle.setType(STStyleType.PARAGRAPH);
            headingStyle.addNewName().setVal("Heading 1");
            headingStyle.addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.ZERO);
            docx.createStyles().addStyle(new XWPFStyle(headingStyle));
            var firstHeading = docx.createParagraph();
            firstHeading.setStyle("Heading1");
            firstHeading.createRun().setText("Section A");
            docx.createTable(1, 1).getRow(0).getCell(0).setText("ZephyrQuota is 42 units.");
            var secondHeading = docx.createParagraph();
            secondHeading.setStyle("Heading1");
            secondHeading.createRun().setText("Section B");
            docx.createParagraph().createRun().setText("Unrelated closing notes.");
            docx.write(output);
            content = output.toByteArray();
        }
        DocumentParsingProperties parsing = new DocumentParsingProperties();
        parsing.getDeepdoc().setBaseUrl(System.getenv("DOCQUERY_DOCX_PARSER_URL"));
        DocumentFormatParser realParser = new DeepDocDocumentParser(parsing, objectMapper);
        // 重现旧实现先输出段落、再追加所有表格的顺序，作为待修复的历史版本。
        DocumentFormatParser legacyParser = new DocumentFormatParser() {
            @Override
            public boolean supports(DocumentSourceFormat format) {
                return realParser.supports(format);
            }

            @Override
            public ParsedDocument parse(ParseSource source) {
                ParsedDocument parsed = realParser.parse(source);
                return new ParsedDocument(parsed.blocks().stream()
                        .sorted(java.util.Comparator.comparing(block -> block.kind() == BlockKind.TABLE_CELL))
                        .toList(), parsed.warnings(), parsed.pageCount());
            }
        };
        var first = upload("DOCX heading repair", "docx-legacy", "heading-repair.docx", content);
        ensureDocxCanonical(first.getDocumentVersionId(), legacyParser, parsing);
        publishPendingOutbox();
        awaitJobStatus(first.getProcessingJobId(), "3", 30_000);
        assertDocxTableHeading(first.getDocumentVersionId(), "Section B");

        var rebuilt = uploadCoordinator.rebuildDocument(tenantAdmin(), tenantId, knowledgeBaseId,
                first.getDocumentId(), "docx-correct-order");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                first.getDocumentId())).isEqualTo(first.getDocumentVersionId());
        assertThat(value("SELECT source_sha256 FROM document_version WHERE id=?", String.class,
                rebuilt.getDocumentVersionId())).isEqualTo(value(
                        "SELECT source_sha256 FROM document_version WHERE id=?", String.class,
                        first.getDocumentVersionId()));
        ensureDocxCanonical(rebuilt.getDocumentVersionId(), realParser, parsing);
        publishPendingOutbox();
        awaitJobStatus(rebuilt.getProcessingJobId(), "3", 30_000);
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                first.getDocumentId())).isEqualTo(rebuilt.getDocumentVersionId());
        assertDocxTableHeading(rebuilt.getDocumentVersionId(), "Section A");
        assertThat(retrievalArtifactMapper.findByDocumentVersionId(rebuilt.getDocumentVersionId()))
                .isNotNull();
        assertThat(projectionMapper.findByDocumentVersionId(rebuilt.getDocumentVersionId()))
                .isNotNull();
    }

    private void ensureDocxCanonical(long versionId, DocumentFormatParser parser,
                                     DocumentParsingProperties parsing) {
        new DocumentCanonicalServiceImpl(versionMapper, documentMapper, canonicalArtifactMapper,
                sourceStore, canonicalStore, List.of(parser), new CanonicalDocumentAssembler(parsing),
                new CanonicalDocumentValidator(), new CanonicalJsonlWriter(objectMapper, parsing), parsing)
                .ensureCanonical(versionId);
    }

    private void assertDocxTableHeading(long versionId, String expectedTitle) throws Exception {
        var artifact = canonicalArtifactMapper.findByDocumentVersionId(versionId);
        try (var input = canonicalStore.open(artifact.getCanonicalObjectKey())) {
            var canonical = new CanonicalArtifactReader(objectMapper, new CanonicalDocumentValidator())
                    .read(input);
            var table = canonical.blocks().stream()
                    .filter(block -> block.kind() == BlockKind.TABLE_CELL && block.text().contains("ZephyrQuota"))
                    .findFirst().orElseThrow();
            assertThat(canonical.headings().stream()
                    .filter(heading -> heading.nodeId().equals(table.headingNodeId()))
                    .findFirst().orElseThrow().title()).isEqualTo(expectedTitle);
            var hits = elasticsearch.search(search -> search.index("docquery-evidence")
                    .query(query -> query.bool(bool -> bool
                            .filter(filter -> filter.term(term -> term.field("document_version_id").value(versionId)))
                            .must(must -> must.match(match -> match.field("text").query("ZephyrQuota"))))), Map.class);
            assertThat(hits.hits().hits()).hasSize(1);
            assertThat(hits.hits().hits().get(0).source())
                    .containsEntry("heading_node_id", table.headingNodeId());
            assertThat((String) hits.hits().hits().get(0).source().get("heading_path"))
                    .contains(expectedTitle);
        }
    }

    @Test
    void readyDocumentDeletesAllVersionContentAndKeepsHistory() throws Exception {
        DocumentUploadAcceptedVO first = upload(
                "Disposable Guide",
                "delete-e2e-v1",
                "delete-v1.md",
                "# Version One\n\nFirst retained fact.\n".getBytes(StandardCharsets.UTF_8)
        );
        publishPendingOutbox();
        awaitJobStatus(first.getProcessingJobId(), "3", 30_000);

        DocumentUploadAcceptedVO second = uploadCoordinator.uploadNewVersion(
                tenantAdmin(),
                tenantId,
                knowledgeBaseId,
                first.getDocumentId(),
                "delete-e2e-v2",
                new MockMultipartFile(
                        "file", "delete-v2.md", "text/markdown",
                        "# Version Two\n\nSecond active fact.\n"
                                .getBytes(StandardCharsets.UTF_8)
                )
        );
        publishPendingOutbox();
        awaitJobStatus(second.getProcessingJobId(), "3", 30_000);

        List<DocumentVersionEntity> versions = versionMapper.findByTenantAndDocument(
                tenantId, first.getDocumentId()
        );
        assertThat(versions).hasSize(2);
        List<String> sourceKeys = versions.stream()
                .map(DocumentVersionEntity::getSourceObjectKey).toList();
        List<String> canonicalKeys = versions.stream()
                .map(version -> canonicalArtifactMapper
                        .findByDocumentVersionId(version.getId()).getCanonicalObjectKey())
                .toList();
        List<String> retrievalKeys = versions.stream()
                .map(version -> retrievalArtifactMapper
                        .findByDocumentVersionId(version.getId()).getRetrievalObjectKey())
                .toList();
        assertThat(sourceKeys).allMatch(sourceStore::exists);
        assertThat(canonicalKeys).allMatch(canonicalStore::exists);
        assertThat(retrievalKeys).allMatch(retrievalStore::exists);

        var accepted = deletionService.deleteDocument(
                tenantAdmin(), tenantId, knowledgeBaseId,
                first.getDocumentId(), "delete-e2e-command"
        );
        assertThat(documentMapper.findActiveVersionSnapshot(
                tenantId, knowledgeBaseId, "1", "2"
        )).isEmpty();
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                first.getDocumentId())).isEqualTo("2");

        publishPendingOutbox();
        awaitDeletionJobStatus(accepted.getDeletionJobId(), "3", 30_000);

        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                first.getDocumentId())).isEqualTo("3");
        assertThat(value("SELECT active_name FROM document WHERE id=?", String.class,
                first.getDocumentId())).isNull();
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                first.getDocumentId())).isNull();
        assertThat(value("""
                SELECT COUNT(*) FROM document_version
                WHERE document_id=? AND content_deleted_at IS NOT NULL
                """, Integer.class, first.getDocumentId())).isEqualTo(2);
        assertThat(count("document_canonical_artifact")).isZero();
        assertThat(count("document_retrieval_artifact")).isZero();
        assertThat(count("document_search_projection")).isZero();
        assertThat(value("SELECT COUNT(*) FROM processing_job", Integer.class)).isEqualTo(2);
        assertThat(value("SELECT COUNT(*) FROM document_deletion_job", Integer.class)).isOne();
        assertThat(value("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(3);
        assertThat(sourceKeys).noneMatch(sourceStore::exists);
        assertThat(canonicalKeys).noneMatch(canonicalStore::exists);
        assertThat(retrievalKeys).noneMatch(retrievalStore::exists);
        for (DocumentVersionEntity version : versions) {
            assertThat(elasticsearch.count(request -> request
                    .index("docquery-evidence")
                    .query(query -> query.term(term -> term
                            .field("document_version_id").value(version.getId())))).count())
                    .isZero();
            assertThat(elasticsearch.count(request -> request
                    .index("docquery-navigation")
                    .query(query -> query.term(term -> term
                            .field("document_version_id").value(version.getId())))).count())
                    .isZero();
        }

        DocumentUploadAcceptedVO replacement = upload(
                "Disposable Guide",
                "delete-e2e-replacement",
                "replacement.md",
                "# Replacement\n\nNew logical document.\n".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(replacement.getDocumentId()).isNotEqualTo(first.getDocumentId());
    }

    @Test
    void writesTwoValidatedIndicesAndRepairsExtraReplayDocument() throws Exception {
        DocumentUploadAcceptedVO upload = upload(
                "Operations Guide",
                "search-1",
                "guide.md",
                "# Install\n\nRun it.\n## Verify\n\nCheck health.\n"
                        .getBytes(StandardCharsets.UTF_8)
        );
        DocumentCanonicalArtifactEntity canonical = canonicalService.ensureCanonical(
                upload.getDocumentVersionId()
        );
        DocumentRetrievalArtifactEntity retrieval = retrievalService.ensureRetrieval(
                upload.getDocumentVersionId()
        );

        DocumentSearchProjectionEntity first = projectionService.ensureProjection(
                upload.getDocumentVersionId()
        );

        assertThat(first.getEvidenceExpectedCount()).isEqualTo(canonical.getBlockCount());
        assertThat(first.getEvidenceActualCount()).isEqualTo(canonical.getBlockCount());
        assertThat(first.getNavigationExpectedCount()).isEqualTo(
                retrieval.getVectorCount()
        );
        assertThat(first.getNavigationActualCount()).isEqualTo(
                retrieval.getVectorCount()
        );
        assertThat(elasticsearch.indices().existsAlias(exists -> exists
                .name("docquery-evidence")).value()).isTrue();
        assertThat(elasticsearch.indices().existsAlias(exists -> exists
                .name("docquery-navigation")).value()).isTrue();

        var textHits = elasticsearch.search(search -> search
                        .index("docquery-evidence")
                        .query(query -> query.match(match -> match
                                .field("text")
                                .query("health"))),
                Map.class
        );
        assertThat(textHits.hits().total().value()).isPositive();
        Map<String, Object> evidenceSource = textHits.hits().hits().get(0).source();
        assertThat(((Number) evidenceSource.get("canonical_artifact_id")).longValue())
                .isEqualTo(canonical.getId());
        assertThat(evidenceSource.get("canonical_artifact_sha256"))
                .isEqualTo(canonical.getCanonicalSha256());

        List<Float> queryVector = new java.util.ArrayList<>(2560);
        for (int index = 0; index < 2560; index++) {
            queryVector.add(index + 1.0f);
        }
        var vectorHits = elasticsearch.search(search -> search
                        .index("docquery-navigation")
                        .knn(knn -> knn
                                .field("embedding")
                                .queryVector(queryVector)
                                .k(1)
                                .numCandidates(10)),
                Map.class
        );
        assertThat(vectorHits.hits().hits()).hasSize(1);
        Map<String, Object> navigationSource = vectorHits.hits().hits().get(0).source();
        assertThat(((Number) navigationSource.get("retrieval_artifact_id")).longValue())
                .isEqualTo(retrieval.getId());
        assertThat(navigationSource)
                .containsEntry("embedding_provider", retrieval.getEmbeddingProvider())
                .containsEntry("embedding_model", retrieval.getEmbeddingModel());
        assertThat((String) navigationSource.get("embedding_input_sha256")).hasSize(64);
        assertThat((String) navigationSource.get("embedding_value_sha256")).hasSize(64);

        // 制造同版本同指纹额外记录，重放必须按完整版本范围清理后恢复精确数量。
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tenant_id", tenantId);
        extra.put("knowledge_base_id", knowledgeBaseId);
        extra.put("document_id", upload.getDocumentId());
        extra.put("document_version_id", upload.getDocumentVersionId());
        extra.put("block_id", "extra-block");
        extra.put("text", "stale");
        extra.put("projection_fingerprint", first.getProjectionFingerprint());
        elasticsearch.index(index -> index
                .index(first.getEvidenceIndexName())
                .id("extra-block")
                .document(extra));
        elasticsearch.indices().refresh(refresh -> refresh.index(
                first.getEvidenceIndexName()));

        DocumentSearchProjectionEntity replay = projectionService.ensureProjection(
                upload.getDocumentVersionId()
        );
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(elasticsearch.exists(exists -> exists
                .index(first.getEvidenceIndexName())
                .id("extra-block")).value()).isFalse();
        assertThat(count("document_search_projection")).isOne();
    }

    @Test
    void activatesOnlyWithCurrentLeaseAndCommitsAllThreeFacts() {
        DocumentUploadAcceptedVO upload = upload(
                "Activation Guide",
                "search-2",
                "activate.md",
                "# Ready\n\nActivation evidence.\n".getBytes(StandardCharsets.UTF_8)
        );
        canonicalService.ensureCanonical(upload.getDocumentVersionId());
        retrievalService.ensureRetrieval(upload.getDocumentVersionId());
        projectionService.ensureProjection(upload.getDocumentVersionId());

        ProcessingJobEntity pending = jobMapper.findInitialByTenantAndVersion(
                tenantId,
                upload.getDocumentVersionId()
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(jobMapper.acquireLease(
                pending.getId(),
                tenantId,
                upload.getDocumentVersionId(),
                "lease-a",
                now.plusMinutes(10),
                now,
                "1",
                "2"
        )).isOne();
        DocumentEntity document = documentMapper.findByTenantAndId(
                tenantId,
                upload.getDocumentId()
        );
        DocumentVersionEntity version = versionMapper.findById(
                upload.getDocumentVersionId()
        );
        ProcessingJobEntity running = jobMapper.findById(pending.getId());
        DocumentProcessingMessage message = new DocumentProcessingMessage(
                1, 1, "1", tenantId, knowledgeBaseId,
                upload.getDocumentId(), upload.getDocumentVersionId(), pending.getId()
        );

        DocumentProcessingException conflict = catchThrowableOfType(
                DocumentProcessingException.class,
                () -> activationService.activate(new DocumentIngestionProcessor.Context(
                        message, document, version, running, "lease-b"
                ))
        );
        assertThat(conflict.code()).isEqualTo("SEARCH_ACTIVATION_CONFLICT");
        assertThat(value("SELECT status FROM document_version WHERE id=?", String.class,
                upload.getDocumentVersionId())).isEqualTo("1");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                upload.getDocumentId())).isNull();
        assertThat(value("SELECT status FROM processing_job WHERE id=?", String.class,
                pending.getId())).isEqualTo("2");

        DocumentIngestionProcessor processor = new DocumentIngestionProcessorImpl(
                canonicalService,
                retrievalService,
                projectionService,
                activationService
        );
        processor.process(new DocumentIngestionProcessor.Context(
                message, document, version, running, "lease-a"
        ));

        assertThat(value("SELECT status FROM document_version WHERE id=?", String.class,
                upload.getDocumentVersionId())).isEqualTo("2");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                upload.getDocumentId())).isEqualTo(upload.getDocumentVersionId());
        assertThat(value("SELECT status FROM processing_job WHERE id=?", String.class,
                pending.getId())).isEqualTo("3");
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
                tenantAdmin(),
                tenantId,
                knowledgeBaseId,
                idempotencyKey,
                metadata,
                new MockMultipartFile("file", filename, "text/markdown", content)
        );
    }

    private AdminPrincipal tenantAdmin() {
        return new AdminPrincipal(
                adminId,
                tenantId,
                "search.admin",
                null,
                "2",
                true
        );
    }

    private void publishPendingOutbox() {
        new OutboxPublisher(
                outboxEventMapper,
                rabbitPublisher,
                messagingProperties,
                objectMapper,
                transactionManager
        ).publishAvailable();
    }

    /** 等待异步 Listener 提交最终状态，并在超时后输出数据库中的真实状态。 */
    private void awaitJobStatus(long jobId, String expected, long timeoutMillis)
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

    private void awaitDeletionJobStatus(long jobId, String expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String status = value(
                    "SELECT status FROM document_deletion_job WHERE id=?",
                    String.class,
                    jobId
            );
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(value(
                "SELECT status FROM document_deletion_job WHERE id=?",
                String.class,
                jobId
        )).isEqualTo(expected);
    }

    private long insertTenant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO tenant (name,status,created_at,updated_at) "
                + "VALUES ('Search Tenant','1',?,?)", now, now);
        return value("SELECT id FROM tenant WHERE name='Search Tenant'", Long.class);
    }

    private long insertAdmin(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, 'search.admin', '{bcrypt}test', '2', '1', ?, ?)
                """, ownerTenantId, now, now);
        return value("SELECT id FROM admin_user WHERE login_name='search.admin'", Long.class);
    }

    private long insertKnowledgeBase(long ownerTenantId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, 'Search KB', NULL, '1', ?, ?)
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
        for (RetrievalArtifactStore.ObjectSummary object
                : retrievalStore.list("retrieval/", 1000)) {
            retrievalStore.delete(object.objectKey());
        }
        for (CanonicalArtifactStore.ObjectSummary object
                : canonicalStore.list("canonical/", 1000)) {
            canonicalStore.delete(object.objectKey());
        }
        for (SourceObjectStore.ObjectSummary object : sourceStore.list("source/", 1000)) {
            sourceStore.delete(object.objectKey());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakePipelineConfig {

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
                DocumentRetrievalProperties properties,
                ChatProfilesProperties chatProfiles
        ) {
            return new RetrievalArtifactGenerator(
                    chat, embedding, semanticValidator,
                    new NavigationPartitionPlanner(properties), textBuilder, codec,
                    fingerprint, properties, chatProfiles, Runnable::run
            );
        }

        /** 测试用假 Gateway 不开启真实供应商，故显式组装生产 N2.4 服务。 */
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

        /** 同理显式组装生产 N2.5 投影服务，ES Adapter 仍使用真实容器。 */
        @Bean
        DocumentSearchProjectionService documentSearchProjectionService(
                DocumentCanonicalService canonicalService,
                DocumentRetrievalService retrievalService,
                CanonicalArtifactStore canonicalStore,
                RetrievalArtifactStore retrievalStore,
                CanonicalArtifactReader canonicalReader,
                RetrievalJsonlReader retrievalReader,
                EmbeddingCodec embeddingCodec,
                SearchProjectionStore searchStore,
                SearchProjectionFingerprint fingerprint,
                SearchProjectionProperties properties,
                DocumentVersionMapper versionMapper,
                DocumentMapper documentMapper,
                DocumentSearchProjectionMapper projectionMapper
        ) {
            return new DocumentSearchProjectionServiceImpl(
                    canonicalService, retrievalService, canonicalStore, retrievalStore,
                    canonicalReader, retrievalReader, embeddingCodec, searchStore,
                    fingerprint, properties, versionMapper, documentMapper, projectionMapper
            );
        }

        /** 用假模型和真实外围依赖组装生产 Processor，供 Rabbit Listener 端到端调用。 */
        @Bean
        DocumentIngestionProcessor documentIngestionProcessor(
                DocumentCanonicalService canonicalService,
                DocumentRetrievalService retrievalService,
                DocumentSearchProjectionService projectionService,
                DocumentVersionActivationService activationService
        ) {
            return new DocumentIngestionProcessorImpl(
                    canonicalService,
                    retrievalService,
                    projectionService,
                    activationService
            );
        }
    }

    static final class FakeChatGateway implements RetrievalCardChatGateway {
        @Override
        public List<GeneratedNode> generateNodes(
                List<NodeInput> inputs,
                String correctionHint
        ) {
            return inputs.stream().map(input -> new GeneratedNode(
                    input.requestId(),
                    new RetrievalNodeSemantic(
                            "Summary " + input.titlePath(),
                            List.of("operations"),
                            List.of(),
                            List.of("What is covered?")
                    )
            )).toList();
        }

        @Override
        public DocumentProfileSemantic generateProfile(
                ProfileInput input,
                String correctionHint
        ) {
            return new DocumentProfileSemantic(
                    "Document purpose",
                    List.of("operations"),
                    List.of(),
                    List.of("What is this document about?")
            );
        }
    }

    static final class FakeEmbeddingGateway implements NavigationEmbeddingGateway {
        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(text -> {
                float[] vector = new float[2560];
                for (int index = 0; index < vector.length; index++) {
                    vector[index] = index + 1.0f;
                }
                return vector;
            }).toList();
        }
    }
}
