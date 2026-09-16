package com.doc.docquery;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.parser.BlockKind;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.CanonicalJsonlWriter;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.parser.SourcePosition;
import com.doc.docquery.search.SearchProjectionDocument;
import com.doc.docquery.search.SearchProjectionScope;
import com.doc.docquery.search.SearchProjectionStore;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.AnswerChatGateway;
import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.AnswerException;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.QueryEmbeddingGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** N3.2/N3.3 使用真实 MySQL、SeaweedFS、Elasticsearch、Redis 和 Fake Provider。 */
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({
        RetrieveIT.FakeQueryEmbeddingConfig.class,
        RetrieveIT.FakeAnswerChatConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "docquery.object-storage.enabled=true",
                "docquery.retrieval.provider-enabled=false",
                "docquery.search.enabled=true",
                "docquery.query.idempotency.enabled=true",
                "docquery.query.idempotency.running-ttl=30s",
                "docquery.query.idempotency.result-ttl=30s",
                "docquery.messaging.infrastructure-enabled=false",
                "docquery.messaging.publisher-enabled=false",
                "docquery.messaging.listener-enabled=false"
        }
)
class RetrieveIT {

    private static final String ACCESS_KEY = "docquery-retrieve-test";
    private static final String SECRET_KEY = "docquery-retrieve-secret";
    private static final String BUCKET = "docquery-source";
    private static final String REDIS_PASSWORD = "retrieve-redis-password";

    private static final long TENANT_ID = 101L;
    private static final long ADMIN_ID = 201L;
    private static final long APPLICATION_ID = 301L;
    private static final long CREDENTIAL_ID = 401L;
    private static final long KNOWLEDGE_BASE_ID = 501L;
    private static final long DOCUMENT_ID = 601L;
    private static final long ACTIVE_VERSION_ID = 701L;
    private static final long HISTORY_VERSION_ID = 702L;
    private static final String KEY_ID = "R".repeat(22);
    private static final String SECRET = "s".repeat(43);
    private static final String TOKEN = "dq_app_" + KEY_ID + '.' + SECRET;
    private static final String PROJECTION_FINGERPRINT = "f".repeat(64);

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
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.8.1")
    )
            .withExposedPorts(6379)
            .withCommand(
                    "redis-server",
                    "--appendonly", "no",
                    "--save", "",
                    "--requirepass", REDIS_PASSWORD
            )
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("docquery.object-storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":"
                        + SEAWEEDFS.getMappedPort(8333));
        registry.add("docquery.object-storage.access-key", () -> ACCESS_KEY);
        registry.add("docquery.object-storage.secret-key", () -> SECRET_KEY);
        registry.add("docquery.object-storage.bucket", () -> BUCKET);
        registry.add("docquery.search.endpoint",
                () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private CanonicalArtifactStore canonicalStore;
    @Autowired
    private DocumentCanonicalArtifactMapper canonicalMapper;
    @Autowired
    private CanonicalJsonlWriter canonicalWriter;
    @Autowired
    private SearchProjectionStore searchStore;
    @Autowired
    private SearchProjectionProperties searchProperties;
    @Autowired
    private ElasticsearchClient elasticsearch;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FakeQueryEmbeddingGateway queryEmbedding;
    @Autowired
    private FakeAnswerChatGateway answerChat;
    @LocalServerPort
    private int serverPort;

    private CanonicalFixture canonical;

    @BeforeEach
    void resetState() throws Exception {
        queryEmbedding.fail = false;
        answerChat.reset();
        Set<String> redisKeys = redisTemplate.keys("*");
        if (redisKeys != null && !redisKeys.isEmpty()) {
            redisTemplate.delete(redisKeys);
        }
        resetDatabase();
        deleteCanonicalObjects();
        searchStore.ensureIndices();
        elasticsearch.deleteByQuery(delete -> delete
                .index(searchProperties.evidenceAlias(), searchProperties.navigationAlias())
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .query(query -> query.matchAll(match -> match))
        );
        elasticsearch.indices().refresh(refresh -> refresh.index(
                searchProperties.evidenceAlias(),
                searchProperties.navigationAlias()
        ));
        insertIdentityAndVersion();
        canonical = createCanonical();
        indexSearchDocuments();
    }

    @Test
    void answerReturnsCanonicalCitationAndExactlyReplaysSuccessfulResult() throws Exception {
        answerChat.script(
                toolTurn("verify", "search", "{\"query\":\"refund policy verification\"}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"ANSWERED\",\"answer\":\"七天内可以退款。\","
                                + "\"evidenceIds\":[\"E1\"]}",
                        List.of()
                )
        );

        MvcResult first = answer(
                "answer-replay-key", "tampered-es-only", "KEYWORD", 1
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value("七天内可以退款。\n\n参考依据：[1]"))
                .andExpect(jsonPath("$.citations[0].citationIndex").value(1))
                .andExpect(jsonPath("$.citations[0].documentVersionId")
                        .value(ACTIVE_VERSION_ID))
                .andExpect(jsonPath("$.citations[0].blockId").value("active-body"))
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();
        assertThat(firstBody).contains("Refunds are allowed within seven days");
        assertThat(firstBody).doesNotContain("tampered-es-only");

        MvcResult replay = answer(
                "answer-replay-key", "tampered-es-only", "KEYWORD", 1
        ).andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(answerChat.calls).isEqualTo(2);
    }

    @Test
    void answerUsesBoundedCanonicalToolsAndReturnsInsufficientAsSuccess() throws Exception {
        answerChat.script(
                toolTurn("search", "search",
                        "{\"query\":\"refund policy verification\"}"),
                toolTurn("outline", "open", "{\"ref\":\"D1\"}"),
                toolTurn("read", "open", "{\"ref\":\"S1\"}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"ANSWERED\",\"answer\":\"七天内可以退款。\","
                                + "\"evidenceIds\":[\"E3\"]}",
                        List.of()
                )
        );
        answer("answer-tools-key", "no-initial-hit", "KEYWORD", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.citations[0].blockId").value("active-heading"));
        assertThat(answerChat.calls).isEqualTo(4);
        Map<String, Object> toolAudit = jdbcTemplate.queryForMap("""
                SELECT tool_rounds, tool_calls, model_calls, canonical_characters,
                       outcome, answer_status
                FROM application_query_audit
                WHERE operation_type='2'
                ORDER BY id DESC LIMIT 1
                """);
        assertThat(((Number) toolAudit.get("tool_rounds")).intValue()).isEqualTo(3);
        assertThat(((Number) toolAudit.get("tool_calls")).intValue()).isEqualTo(3);
        assertThat(((Number) toolAudit.get("model_calls")).intValue()).isEqualTo(4);
        assertThat(((Number) toolAudit.get("canonical_characters")).intValue())
                .isGreaterThan(0);
        assertThat(toolAudit.get("outcome")).isEqualTo("2");
        assertThat(toolAudit.get("answer_status")).isEqualTo("ANSWERED");

        answerChat.reset();
        answerChat.script(
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"evidenceIds\":[]}",
                        List.of()
                ),
                toolTurn("search-a", "search", "{\"query\":\"no evidence alternative\"}"),
                toolTurn("search-b", "search", "{\"query\":\"missing source synonym\"}"),
                new AnswerChatGateway.Turn(
                        "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"answer\":null,"
                                + "\"evidenceIds\":[]}",
                        List.of()
                )
        );
        answer("answer-insufficient-key", "no-evidence", "KEYWORD", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    void answerModelFailureIs503AndDoesNotBreakRetrieve() throws Exception {
        answerChat.fail = true;
        answer("answer-model-failure-key", "refund", "KEYWORD", 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANSWER_MODEL_UNAVAILABLE"));

        retrieve("retrieve-after-answer-failure", "refund", "KEYWORD", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].documentVersionId")
                        .value(ACTIVE_VERSION_ID));
    }

    @Test
    void threeModesFilterActiveVersionReturnCanonicalAndReplay() throws Exception {
        MvcResult keyword = retrieve(
                "keyword-key",
                "tampered-es-only",
                "KEYWORD",
                1
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.executedMode").value("KEYWORD"))
                .andExpect(jsonPath("$.results[0].documentVersionId")
                        .value(ACTIVE_VERSION_ID))
                .andExpect(jsonPath("$.results[0].channels[0]").value("KEYWORD"))
                .andReturn();
        String keywordBody = keyword.getResponse().getContentAsString();
        assertThat(keywordBody).contains("Refunds are allowed within seven days");
        assertThat(keywordBody).doesNotContain("tampered-es-only");

        retrieve("semantic-key", "refund requirement", "SEMANTIC", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executedMode").value("SEMANTIC"))
                .andExpect(jsonPath("$.results[0].channels[0]").value("SEMANTIC"))
                .andExpect(jsonPath("$.results[0].evidence[0].blockId")
                        .value("active-later"));

        MvcResult hybrid = retrieve(
                "hybrid-replay-key",
                "tampered-es-only",
                "HYBRID",
                1
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.executedMode").value("HYBRID"))
                .andExpect(jsonPath("$.results[0].channels.length()").value(2))
                .andReturn();
        String executionId = objectMapper.readTree(
                hybrid.getResponse().getContentAsString()
        ).get("queryExecutionId").asText();

        // 删除本次 activeVersion 的两个 ES 投影后，相同 Key 仍精确重放成功结果。
        searchStore.deleteScope(scope(ACTIVE_VERSION_ID));
        searchStore.refresh();
        retrieve("hybrid-replay-key", "tampered-es-only", "HYBRID", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryExecutionId").value(executionId))
                .andExpect(jsonPath("$.results[0].documentVersionId")
                        .value(ACTIVE_VERSION_ID));
    }

    @Test
    void embeddingFailureDegradesHybridButSemanticFailsClosed() throws Exception {
        queryEmbedding.fail = true;

        retrieve("hybrid-degraded-key", "tampered-es-only", "HYBRID", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executedMode").value("KEYWORD"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradationReason")
                        .value("QUERY_EMBEDDING_UNAVAILABLE"));

        retrieve("semantic-unavailable-key", "refund", "SEMANTIC", 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUERY_EMBEDDING_UNAVAILABLE"));
    }

    @Test
    void replayRechecksCredentialGrantAndActiveSnapshot() throws Exception {
        retrieve("security-replay-key", "tampered-es-only", "KEYWORD", 1)
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE credential SET status='3' WHERE id=?", CREDENTIAL_ID);
        retrieve("security-replay-key", "tampered-es-only", "KEYWORD", 1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("APPLICATION_CREDENTIAL_INVALID"));

        jdbcTemplate.update("UPDATE credential SET status='1' WHERE id=?", CREDENTIAL_ID);
        insertVersion(703L, 2, "2");
        jdbcTemplate.update(
                "UPDATE document SET active_version_id=703, latest_version_id=703 WHERE id=?",
                DOCUMENT_ID
        );
        retrieve("security-replay-key", "tampered-es-only", "KEYWORD", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONTEXT_CHANGED"));

        jdbcTemplate.update(
                "UPDATE application_grant SET status='3' WHERE application_id=? AND knowledge_base_id=?",
                APPLICATION_ID,
                KNOWLEDGE_BASE_ID
        );
        retrieve("new-key-after-revoke", "refund", "KEYWORD", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_AVAILABLE"));
    }

    @Test
    void corruptedCanonicalFailsClosedAndInvalidRequestIsStable() throws Exception {
        byte[] corrupted = "{\"recordType\":\"header\"}\n".getBytes(StandardCharsets.UTF_8);
        canonicalStore.put(
                canonical.objectKey(),
                new ByteArrayInputStream(corrupted),
                corrupted.length,
                1024
        );

        retrieve("canonical-corrupted-key", "tampered-es-only", "KEYWORD", 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EVIDENCE_UNAVAILABLE"));

        mockMvc.perform(post(path())
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "invalid-request-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \",\"mode\":\"HYBRID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RETRIEVE_REQUEST"));
    }

    @Test
    void applicationAuditSeparatesOwnerReplayAndRejectedAttempts() throws Exception {
        MvcResult owner = mockMvc.perform(post(path())
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "audit-replay-key")
                        .header("X-DocQuery-Trace-Id", "work-order-4711")
                        .header("X-DocQuery-Actor-Ref", "actor-sha-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", "refund secret phrase",
                                "mode", "KEYWORD",
                                "topK", 1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String firstRequestId = owner.getResponse().getHeader("X-DocQuery-Request-Id");
        assertThat(firstRequestId).isNotBlank();

        retrieve("audit-replay-key", "refund secret phrase", "KEYWORD", 1)
                .andExpect(status().isOk());
        List<Map<String, Object>> successful = jdbcTemplate.queryForList("""
                SELECT request_id, query_execution_id, idempotency_disposition,
                       caller_trace_id, actor_ref, query_sha256, outcome,
                       result_count, evidence_count
                FROM application_query_audit
                WHERE operation_type='1' AND outcome='2'
                ORDER BY id
                """);
        assertThat(successful).hasSize(2);
        assertThat(successful.get(0).get("request_id")).isEqualTo(firstRequestId);
        assertThat(successful).extracting(row -> row.get("idempotency_disposition"))
                .containsExactly("1", "2");
        assertThat(successful.get(0).get("query_execution_id"))
                .isEqualTo(successful.get(1).get("query_execution_id"));
        assertThat(successful.get(0).get("caller_trace_id"))
                .isEqualTo("work-order-4711");
        assertThat(successful.get(0).get("actor_ref")).isEqualTo("actor-sha-9");
        assertThat(successful.get(0).get("query_sha256"))
                .isNotEqualTo("refund secret phrase");

        retrieve("audit-replay-key", "different question", "KEYWORD", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT outcome, failure_category, failure_code,
                       idempotency_disposition
                FROM application_query_audit ORDER BY id DESC LIMIT 1
                """))
                .containsEntry("outcome", "4")
                .containsEntry("failure_category", "IDEMPOTENCY")
                .containsEntry("failure_code", "IDEMPOTENCY_CONFLICT")
                .containsEntry("idempotency_disposition", "4");

        queryEmbedding.fail = true;
        retrieve("audit-dependency-key", "refund", "SEMANTIC", 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUERY_EMBEDDING_UNAVAILABLE"));
        queryEmbedding.fail = false;
        assertThat(jdbcTemplate.queryForMap("""
                SELECT outcome, failure_category, failure_code
                FROM application_query_audit ORDER BY id DESC LIMIT 1
                """))
                .containsEntry("outcome", "3")
                .containsEntry("failure_category", "QUERY_EMBEDDING")
                .containsEntry("failure_code", "QUERY_EMBEDDING_UNAVAILABLE");

        jdbcTemplate.update(
                "UPDATE application_grant SET status='3' WHERE application_id=? AND knowledge_base_id=?",
                APPLICATION_ID,
                KNOWLEDGE_BASE_ID
        );
        retrieve("audit-denied-key", "refund", "KEYWORD", 1)
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT outcome FROM application_query_audit ORDER BY id DESC LIMIT 1
                """, String.class)).isEqualTo("4");
        long audited = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_query_audit", Long.class
        );
        mockMvc.perform(post(path())
                        .header("Authorization", "Bearer dq_app_invalid")
                        .header("Idempotency-Key", "invalid-credential-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"refund\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_query_audit", Long.class
        )).isEqualTo(audited);

        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, TENANT_ID, "retrieve-admin", null, "2", true
        );
        UsernamePasswordAuthenticationToken adminAuthentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                );
        MvcResult auditPage = mockMvc.perform(
                        get("/api/admin/v1/tenants/" + TENANT_ID + "/query-audits")
                        .param("operation", "RETRIEVE")
                        .param("size", "10")
                        .with(authentication(adminAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[0].outcome").value("REJECTED"))
                .andReturn();
        String auditBody = auditPage.getResponse().getContentAsString();
        assertThat(auditBody)
                .doesNotContain("refund secret phrase")
                .doesNotContain(TOKEN)
                .doesNotContain(SECRET);

        long latestAuditId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM application_query_audit", Long.class
        );
        mockMvc.perform(get("/api/admin/v1/tenants/" + TENANT_ID
                        + "/query-audits/" + latestAuditId)
                        .with(authentication(adminAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID));
        mockMvc.perform(get("/api/admin/v1/tenants/999/query-audits")
                        .with(authentication(adminAuthentication)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/v1/tenants/" + TENANT_ID + "/query-audits")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void externalHttpProducesOwnerAndReplayPerformanceSnapshot() throws Exception {
        int retrieveRequests = 20;
        int answerRequests = 10;
        List<AnswerChatGateway.Turn> scriptedAnswers = new ArrayList<>();
        for (int index = 0; index < answerRequests; index++) {
            scriptedAnswers.add(toolTurn(
                    "verify-" + index,
                    "search",
                    "{\"query\":\"refund policy verification " + index + "\"}"
            ));
            scriptedAnswers.add(new AnswerChatGateway.Turn(
                    "{\"status\":\"ANSWERED\",\"answer\":\"七天内可以退款。\"," +
                            "\"evidenceIds\":[\"E1\"]}",
                    List.of()
            ));
        }
        answerChat.script(scriptedAnswers.toArray(AnswerChatGateway.Turn[]::new));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "n3-fake-http-performance-v1");
        report.put("scope", "DETERMINISTIC_FAKE_GATEWAY");
        report.put("transport", "EMBEDDED_HTTP");
        report.put("concurrency", 1);
        report.put("jdk", System.getProperty("java.version"));
        Map<String, Object> metrics = new LinkedHashMap<>();
        benchmarkOperation(
                client, "RETRIEVE", path(), retrieveRequests,
                "{\"query\":\"tampered-es-only\",\"mode\":\"KEYWORD\",\"topK\":1}",
                metrics
        );
        benchmarkOperation(
                client, "ANSWER", answerPath(), answerRequests,
                "{\"query\":\"tampered-es-only\",\"mode\":\"KEYWORD\",\"topK\":1}",
                metrics
        );
        report.put("metrics", metrics);
        report.put("auditRows", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_query_audit", Long.class
        ));

        Path output = Path.of("target", "n3.4-reports", "fake-http-performance.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8
        );
        assertThat(answerChat.calls).isEqualTo(answerRequests * 2);
        assertThat(report.get("auditRows")).isEqualTo(60L);
    }

    private void benchmarkOperation(
            HttpClient client,
            String operation,
            String endpoint,
            int requests,
            String body,
            Map<String, Object> metrics
    ) throws Exception {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < requests; index++) {
            keys.add("n3-perf-" + operation.toLowerCase() + '-' + index);
        }
        metrics.put(operation + "_OWNER", executeHttpBatch(
                client, endpoint, keys, body
        ));
        metrics.put(operation + "_REPLAY", executeHttpBatch(
                client, endpoint, keys, body
        ));
    }

    private Map<String, Object> executeHttpBatch(
            HttpClient client,
            String endpoint,
            List<String> keys,
            String body
    ) throws Exception {
        List<Double> latencies = new ArrayList<>();
        long batchStart = System.nanoTime();
        for (String key : keys) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + serverPort + endpoint))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            long started = System.nanoTime();
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            latencies.add((System.nanoTime() - started) / 1_000_000.0);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-DocQuery-Request-Id")).isPresent();
        }
        double elapsedSeconds = (System.nanoTime() - batchStart) / 1_000_000_000.0;
        List<Double> ordered = new ArrayList<>(latencies);
        Collections.sort(ordered);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", keys.size());
        result.put("successes", keys.size());
        result.put("throughputPerSecond", round(keys.size() / elapsedSeconds));
        result.put("p50Ms", round(percentile(ordered, 0.50)));
        result.put("p95Ms", round(percentile(ordered, 0.95)));
        result.put("p99Ms", round(percentile(ordered, 0.99)));
        return result;
    }

    private double percentile(List<Double> ordered, double quantile) {
        int index = Math.max(0, (int) Math.ceil(quantile * ordered.size()) - 1);
        return ordered.get(index);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private org.springframework.test.web.servlet.ResultActions retrieve(
            String idempotencyKey,
            String query,
            String mode,
            int topK
    ) throws Exception {
        return mockMvc.perform(post(path())
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "query", query,
                        "mode", mode,
                        "topK", topK
                ))));
    }

    private org.springframework.test.web.servlet.ResultActions answer(
            String idempotencyKey,
            String query,
            String mode,
            int topK
    ) throws Exception {
        return mockMvc.perform(post(answerPath())
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "query", query,
                        "mode", mode,
                        "topK", topK
                ))));
    }

    private AnswerChatGateway.Turn toolTurn(
            String id,
            String name,
            String arguments
    ) {
        return new AnswerChatGateway.Turn(
                null,
                List.of(new AnswerChatGateway.ToolCall(id, name, arguments))
        );
    }

    private String path() {
        return "/api/v1/service/knowledge-bases/" + KNOWLEDGE_BASE_ID + "/retrieve";
    }

    private String answerPath() {
        return "/api/v1/service/knowledge-bases/" + KNOWLEDGE_BASE_ID + "/answer";
    }

    private void insertIdentityAndVersion() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO tenant (id,name,status,created_at,updated_at) VALUES (?,?, '1',?,?)",
                TENANT_ID, "Retrieve Tenant", now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    id,tenant_id,login_name,password_hash,role,status,created_at,updated_at
                ) VALUES (?,?,?,'{bcrypt}test','2','1',?,?)
                """,
                ADMIN_ID, TENANT_ID, "retrieve-admin", now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO application (
                    id,tenant_id,code,name,environment,description,status,created_at,updated_at
                ) VALUES (?,?,?,?,'TEST',NULL,'1',?,?)
                """,
                APPLICATION_ID, TENANT_ID, "retrieve-app", "Retrieve App", now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    id,tenant_id,name,description,status,created_at,updated_at
                ) VALUES (?,?,?,NULL,'1',?,?)
                """,
                KNOWLEDGE_BASE_ID, TENANT_ID, "Retrieve Knowledge", now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO credential (
                    id,application_id,name,key_id,secret_digest,status,created_at,
                    last_used_at,revoked_at,created_by,revoked_by
                ) VALUES (?,?,?, ?,?,'1',?,NULL,NULL,?,NULL)
                """,
                CREDENTIAL_ID, APPLICATION_ID, "Retrieve Credential", KEY_ID,
                sha256Bytes(SECRET), now, ADMIN_ID
        );
        jdbcTemplate.update(
                """
                INSERT INTO application_grant (
                    id,tenant_id,application_id,knowledge_base_id,permission,status,
                    granted_at,granted_by,revoked_at,revoked_by
                ) VALUES (801,?,?,?,'1','1',?,?,NULL,NULL)
                """,
                TENANT_ID, APPLICATION_ID, KNOWLEDGE_BASE_ID, now, ADMIN_ID
        );
        jdbcTemplate.update(
                """
                INSERT INTO document (
                    id,tenant_id,knowledge_base_id,name,status,active_version_id,
                    latest_version_id,created_by_admin_id,created_at,updated_at
                ) VALUES (?,?,?,'Refund Manual','1',?,?,?, ?,?)
                """,
                DOCUMENT_ID, TENANT_ID, KNOWLEDGE_BASE_ID,
                ACTIVE_VERSION_ID, ACTIVE_VERSION_ID, ADMIN_ID, now, now
        );
        insertVersion(ACTIVE_VERSION_ID, 1, "2");
        insertVersion(HISTORY_VERSION_ID, 0, "2");
    }

    private void insertVersion(long id, int versionNo, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO document_version (
                    id,tenant_id,document_id,version_no,status,source_format,
                    original_filename,source_bucket,source_object_key,source_size_bytes,
                    source_sha256,source_content_type,idempotency_key_hash,
                    request_fingerprint,accepted_by_admin_id,failure_code,failure_message,
                    ready_at,failed_at,created_at,updated_at
                ) VALUES (
                    ?,?,?,?,?,'4','refund.md','docquery-source',?,1,?,
                    'text/markdown',?,?,?,NULL,NULL,?,NULL,?,?
                )
                """,
                id, TENANT_ID, DOCUMENT_ID, versionNo, status,
                "source/" + id, sha256("source-" + id),
                sha256("upload-key-" + id), sha256("upload-request-" + id),
                ADMIN_ID, "2".equals(status) ? now : null, now, now
        );
    }

    private CanonicalFixture createCanonical() throws IOException {
        String headingText = "Refund Policy";
        String bodyText = "Refunds are allowed within seven days.";
        EvidenceBlock heading = new EvidenceBlock(
                "active-heading", 0, BlockKind.HEADING, headingText,
                0, headingText.length(), "active-section", null,
                SourcePosition.markdown(1, 1, 1, headingText.length())
        );
        EvidenceBlock body = new EvidenceBlock(
                "active-body", 1, BlockKind.PARAGRAPH, bodyText,
                heading.canonicalEnd() + 2,
                heading.canonicalEnd() + 2 + bodyText.length(),
                "active-section", null,
                SourcePosition.markdown(3, 1, 3, bodyText.length())
        );
        String laterText = "Enterprise refunds require executive approval.";
        EvidenceBlock later = new EvidenceBlock(
                "active-later", 2, BlockKind.PARAGRAPH, laterText,
                body.canonicalEnd() + 2,
                body.canonicalEnd() + 2 + laterText.length(),
                "active-section", null,
                SourcePosition.markdown(5, 1, 5, laterText.length())
        );
        String canonicalText = headingText + "\n\n" + bodyText + "\n\n" + laterText;
        CanonicalDocument document = new CanonicalDocument(
                1, ACTIVE_VERSION_ID, "MARKDOWN", sha256("source-active"),
                "markdown", "1", Instant.parse("2026-08-11T00:00:00Z"),
                List.of(heading, body, later),
                List.of(
                        new HeadingNode(
                                "root", null, 0, 0, "Refund Manual", null,
                                "DOCUMENT_ROOT", 0, 3
                        ),
                        new HeadingNode(
                                "active-section", "root", 1, 1, headingText,
                                heading.blockId(), "MARKDOWN", 0, 3
                        )
                ),
                List.of(), null, canonicalText.length(), sha256(canonicalText)
        );
        new CanonicalDocumentValidator().validate(document);
        CanonicalJsonlWriter.WrittenArtifact written = canonicalWriter.write(document);
        try {
            byte[] bytes = Files.readAllBytes(written.path());
            String objectKey = "canonical/" + TENANT_ID + "/" + ACTIVE_VERSION_ID
                    + "/retrieve-it.jsonl";
            CanonicalArtifactStore.WriteResult stored = canonicalStore.put(
                    objectKey,
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    1024 * 1024
            );
            DocumentCanonicalArtifactEntity manifest = new DocumentCanonicalArtifactEntity(
                    null, TENANT_ID, ACTIVE_VERSION_ID, 1, "MARKDOWN", "markdown", "1",
                    canonicalStore.bucketName(), objectKey, stored.sizeBytes(), stored.sha256(),
                    document.canonicalTextSha256(), document.textLength(),
                    document.blocks().size(), document.headings().size(), 0, null,
                    LocalDateTime.now()
            );
            assertThat(canonicalMapper.insert(manifest)).isOne();
            return new CanonicalFixture(document, manifest, objectKey);
        } finally {
            Files.deleteIfExists(written.path());
        }
    }

    private void indexSearchDocuments() {
        EvidenceBlock body = canonical.document().blocks().get(1);
        Map<String, Object> evidence = common(ACTIVE_VERSION_ID, PROJECTION_FINGERPRINT);
        evidence.put("block_id", body.blockId());
        evidence.put("ordinal", body.ordinal());
        evidence.put("kind", body.kind().name());
        evidence.put("document_title", "Refund Manual");
        evidence.put("heading_node_id", body.headingNodeId());
        evidence.put("heading_path", "Refund Manual > Refund Policy");
        // ES 文本故意含 canonical 不存在的词，响应仍必须返回 canonical 原文。
        evidence.put("text", "tampered-es-only refund refund refund");
        evidence.put("canonical_start", body.canonicalStart());
        evidence.put("canonical_end", body.canonicalEnd());
        evidence.put("source_type", "MARKDOWN");
        evidence.put("start_line", 3);
        evidence.put("start_column", 1);
        evidence.put("end_line", 3);
        evidence.put("end_column", body.text().length());
        evidence.put("canonical_artifact_id", canonical.manifest().getId());
        evidence.put("canonical_artifact_sha256", canonical.manifest().getCanonicalSha256());
        evidence.put("mapping_version", searchProperties.getEvidenceMappingVersion());
        searchStore.indexEvidence(List.of(new SearchProjectionDocument(
                body.blockId(), evidence
        )));

        Map<String, Object> navigation = common(
                ACTIVE_VERSION_ID,
                PROJECTION_FINGERPRINT
        );
        navigation.put("card_id", "active-card");
        navigation.put("card_type", "HEADING_NODE");
        navigation.put("document_title", "Refund Manual");
        navigation.put("heading_node_id", "active-section");
        navigation.put("parent_heading_node_id", "root");
        navigation.put("sibling_order", 0);
        navigation.put("depth", 1);
        navigation.put("title", "Refund Policy");
        navigation.put("title_path", "Refund Manual > Refund Policy");
        navigation.put("section_start_block_ordinal", 0);
        navigation.put("section_end_block_ordinal_exclusive", 2);
        navigation.put("canonical_start", canonical.document().blocks().get(0).canonicalStart());
        navigation.put("canonical_end", body.canonicalEnd());
        navigation.put("summary", "Refund requirement and policy");
        navigation.put("topics", List.of("refund"));
        navigation.put("aliases", List.of("money back"));
        navigation.put("answerable_questions", List.of("When can I get a refund?"));
        navigation.put("embedding_provider", "FAKE");
        navigation.put("embedding_model", "qwen3.7-text-embedding");
        navigation.put("embedding_dimension", 2560);
        navigation.put("embedding_template_version", "retrieval-embedding-v1");
        navigation.put("embedding_input_sha256", "1".repeat(64));
        navigation.put("embedding_value_sha256", "2".repeat(64));
        navigation.put("embedding", weakVector());
        navigation.put("canonical_artifact_id", canonical.manifest().getId());
        navigation.put("canonical_artifact_sha256", canonical.manifest().getCanonicalSha256());
        navigation.put("retrieval_artifact_id", 901L);
        navigation.put("retrieval_artifact_sha256", "3".repeat(64));
        navigation.put("mapping_version", searchProperties.getNavigationMappingVersion());
        searchStore.indexNavigation(List.of(new SearchProjectionDocument(
                "active-card", navigation
        )));

        EvidenceBlock later = canonical.document().blocks().get(2);
        Map<String, Object> subpartition = new LinkedHashMap<>(navigation);
        subpartition.put("card_id", "active-subpartition");
        subpartition.put("card_type", "HEADING_SUBPARTITION");
        subpartition.put("title", "Enterprise refund approval");
        subpartition.put("title_path",
                "Refund Manual > Refund Policy > Enterprise refund approval");
        subpartition.put("section_start_block_ordinal", 2);
        subpartition.put("section_end_block_ordinal_exclusive", 3);
        subpartition.put("canonical_start", later.canonicalStart());
        subpartition.put("canonical_end", later.canonicalEnd());
        subpartition.put("embedding", vector());
        searchStore.indexNavigation(List.of(new SearchProjectionDocument(
                "active-subpartition", subpartition
        )));

        // 历史版本与其他租户都包含更强关键词；前置过滤后不能进入 Java 候选。
        Map<String, Object> historical = new LinkedHashMap<>(evidence);
        historical.put("document_version_id", HISTORY_VERSION_ID);
        historical.put("block_id", "historical-body");
        historical.put("text", "tampered-es-only ".repeat(20));
        historical.put("projection_fingerprint", "e".repeat(64));
        searchStore.indexEvidence(List.of(new SearchProjectionDocument(
                "historical-body", historical
        )));
        Map<String, Object> crossTenant = new LinkedHashMap<>(evidence);
        crossTenant.put("tenant_id", 999L);
        crossTenant.put("document_version_id", 999L);
        crossTenant.put("block_id", "cross-tenant-body");
        crossTenant.put("text", "tampered-es-only ".repeat(30));
        crossTenant.put("projection_fingerprint", "d".repeat(64));
        searchStore.indexEvidence(List.of(new SearchProjectionDocument(
                "cross-tenant-body", crossTenant
        )));
        searchStore.refresh();
    }

    private Map<String, Object> common(long versionId, String fingerprint) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("tenant_id", TENANT_ID);
        source.put("knowledge_base_id", KNOWLEDGE_BASE_ID);
        source.put("document_id", DOCUMENT_ID);
        source.put("document_version_id", versionId);
        source.put("projection_fingerprint", fingerprint);
        return source;
    }

    private List<Float> vector() {
        List<Float> vector = new ArrayList<>(2560);
        for (int index = 0; index < 2560; index++) {
            vector.add(index == 0 ? 1.0f : 0.001f);
        }
        return vector;
    }

    private List<Float> weakVector() {
        List<Float> vector = new ArrayList<>(2560);
        for (int index = 0; index < 2560; index++) {
            vector.add(index == 1 ? 1.0f : 0.0f);
        }
        return vector;
    }

    private SearchProjectionScope scope(long versionId) {
        return new SearchProjectionScope(
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                DOCUMENT_ID,
                versionId,
                PROJECTION_FINGERPRINT
        );
    }

    private void resetDatabase() {
        jdbcTemplate.update("DELETE FROM application_query_audit");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_search_projection");
        jdbcTemplate.update("DELETE FROM document_retrieval_artifact");
        jdbcTemplate.update("DELETE FROM document_canonical_artifact");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
    }

    private void deleteCanonicalObjects() {
        for (CanonicalArtifactStore.ObjectSummary object : canonicalStore.list(
                "canonical/",
                1000
        )) {
            canonicalStore.delete(object.objectKey());
        }
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record CanonicalFixture(
            CanonicalDocument document,
            DocumentCanonicalArtifactEntity manifest,
            String objectKey
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeQueryEmbeddingConfig {
        @Bean
        FakeQueryEmbeddingGateway fakeQueryEmbeddingGateway() {
            return new FakeQueryEmbeddingGateway();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeAnswerChatConfig {
        @Bean
        FakeAnswerChatGateway fakeAnswerChatGateway() {
            return new FakeAnswerChatGateway();
        }
    }

    static final class FakeQueryEmbeddingGateway implements QueryEmbeddingGateway {
        private volatile boolean fail;

        @Override
        public float[] embedQuery(String query) {
            if (fail) {
                throw new IllegalStateException("fake embedding unavailable");
            }
            float[] vector = new float[2560];
            vector[0] = 1.0f;
            for (int index = 1; index < vector.length; index++) {
                vector[index] = 0.001f;
            }
            return vector;
        }
    }

    static final class FakeAnswerChatGateway
            implements AnswerChatGateway, AnswerAgentGateway {
        private final Deque<Turn> turns = new ArrayDeque<>();
        private volatile boolean fail;
        private int calls;

        void reset() {
            turns.clear();
            fail = false;
            calls = 0;
        }

        void script(Turn... scripted) {
            turns.addAll(List.of(scripted));
        }

        @Override
        public Turn chat(List<Message> messages, boolean toolsEnabled) {
            calls++;
            if (fail) {
                throw new AnswerException(
                        AnswerException.Reason.MODEL_UNAVAILABLE,
                        "fake answer unavailable"
                );
            }
            if (turns.isEmpty()) {
                throw new AssertionError("Fake Answer Model has no scripted turn");
            }
            return turns.removeFirst();
        }

        @Override
        public AgentRun start(Request request, ToolHandler tools, Observer observer) {
            List<Message> conversation = new ArrayList<>();
            conversation.add(new SystemPrompt(request.systemPrompt()));
            return userMessage -> {
                conversation.add(new UserContent(userMessage));
                while (true) {
                    observer.beforeModelCall();
                    Turn turn = chat(List.copyOf(conversation), true);
                    if (!turn.hasToolCalls()) {
                        conversation.add(new AssistantContent(turn.text(), List.of()));
                        return turn.text();
                    }
                    observer.toolRound(turn.toolCalls().size());
                    conversation.add(new AssistantContent(turn.text(), turn.toolCalls()));
                    for (ToolCall call : turn.toolCalls()) {
                        String result = tools.execute(call.name(), call.argumentsJson());
                        conversation.add(new ToolResultContent(call.id(), call.name(), result));
                        observer.afterToolCall();
                    }
                }
            };
        }

    }
}
