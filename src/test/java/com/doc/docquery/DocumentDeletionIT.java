package com.doc.docquery;

import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentDeletionService;
import com.doc.docquery.vo.DocumentDeletionAcceptedVO;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** N4.2 删除受理、权限、幂等、并发和人工重试的真实 MySQL 验收。 */
@Testcontainers
@SpringBootTest(properties = {
        "docquery.object-storage.enabled=false",
        "docquery.messaging.infrastructure-enabled=false",
        "docquery.messaging.publisher-enabled=false",
        "docquery.messaging.listener-enabled=false"
})
@AutoConfigureMockMvc
class DocumentDeletionIT {

    private static final String PASSWORD = "Correct Horse Battery 2026!";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DocumentDeletionService deletionService;
    @Autowired
    private DocumentMapper documentMapper;

    private long tenantA;
    private long tenantB;
    private long adminA;
    private long knowledgeBaseA;
    private long knowledgeBaseB;

    @BeforeEach
    void resetDatabase() {
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

        tenantA = insertTenant("Deletion Tenant A");
        tenantB = insertTenant("Deletion Tenant B");
        adminA = insertAdmin(tenantA, "deletion-a.admin", "2");
        insertAdmin(tenantB, "deletion-b.admin", "2");
        insertAdmin(null, "deletion.platform", "1");
        knowledgeBaseA = insertKnowledgeBase(tenantA, "Deletion KB A", "1");
        knowledgeBaseB = insertKnowledgeBase(tenantB, "Deletion KB B", "1");
    }

    @Test
    void acceptedDeletionImmediatelyLeavesSnapshotAndIsDurableAndIdempotent()
            throws Exception {
        Graph graph = insertReadyDocument(tenantA, knowledgeBaseA, adminA, "Delete Guide");
        Graph other = insertReadyDocument(tenantA, knowledgeBaseA, adminA, "Other Guide");
        AuthSession admin = login("deletion-a.admin");
        String endpoint = endpoint(tenantA, knowledgeBaseA, graph.documentId());

        assertThat(documentMapper.findActiveVersionSnapshot(
                tenantA, knowledgeBaseA, "1", "2"
        )).hasSize(2);

        MvcResult accepted = mockMvc.perform(delete(endpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "delete-command-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value(graph.documentId()))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.documentStatus").value("2"))
                .andExpect(jsonPath("$.jobStatus").value("1"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        long jobId = JsonPath.<Number>read(
                accepted.getResponse().getContentAsString(), "$.deletionJobId"
        ).longValue();

        assertThat(documentMapper.findActiveVersionSnapshot(
                tenantA, knowledgeBaseA, "1", "2"
        )).extracting("documentId").containsExactly(other.documentId());
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                graph.documentId())).isEqualTo("2");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                graph.documentId())).isNull();
        assertThat(value("SELECT COUNT(*) FROM document_deletion_job WHERE document_id=?",
                Integer.class, graph.documentId())).isOne();
        assertThat(value("SELECT COUNT(*) FROM outbox_event WHERE document_deletion_job_id=?",
                Integer.class, jobId)).isOne();

        mockMvc.perform(delete(endpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "delete-command-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deletionJobId").value(jobId))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(delete(endpoint(tenantA, knowledgeBaseA, other.documentId()))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "delete-command-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(get(endpoint).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("2"))
                .andExpect(jsonPath("$.activeVersion").doesNotExist())
                .andExpect(jsonPath("$.latestDeletionJob.deletionJobId").value(jobId))
                .andExpect(jsonPath("$.latestDeletionJob.requestedByAdminId").value(adminA));
    }

    @Test
    void deletionAllowsDisabledKnowledgeBaseButRejectsIngestionInProgress()
            throws Exception {
        long disabledKb = insertKnowledgeBase(tenantA, "Disabled Deletion KB", "2");
        Graph disabled = insertReadyDocument(tenantA, disabledKb, adminA, "Disabled KB Guide");
        Graph processing = insertProcessingDocument(
                tenantA, knowledgeBaseA, adminA, "Processing Guide"
        );
        AuthSession admin = login("deletion-a.admin");

        mockMvc.perform(delete(endpoint(tenantA, disabledKb, disabled.documentId()))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "delete-disabled-kb"))
                .andExpect(status().isAccepted());

        mockMvc.perform(delete(endpoint(tenantA, knowledgeBaseA, processing.documentId()))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "delete-processing"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_PROCESSING_IN_PROGRESS"));
        assertThat(value("SELECT status FROM document WHERE id=?", String.class,
                processing.documentId())).isEqualTo("1");
    }

    @Test
    void deletionEnforcesTenantRoleAndApplicationBoundary() throws Exception {
        Graph foreign = insertReadyDocument(
                tenantB,
                knowledgeBaseB,
                value("SELECT id FROM admin_user WHERE login_name='deletion-b.admin'", Long.class),
                "Foreign Delete Guide"
        );
        AuthSession adminA = login("deletion-a.admin");
        AuthSession platform = login("deletion.platform");
        String foreignEndpoint = endpoint(tenantB, knowledgeBaseB, foreign.documentId());

        mockMvc.perform(delete(foreignEndpoint)
                        .session(adminA.session())
                        .header(adminA.csrfHeader(), adminA.csrfToken())
                        .header("Idempotency-Key", "foreign-delete"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));

        mockMvc.perform(delete(endpoint(tenantA, knowledgeBaseA, foreign.documentId()))
                        .session(adminA.session())
                        .header(adminA.csrfHeader(), adminA.csrfToken())
                        .header("Idempotency-Key", "hidden-delete"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(delete(endpoint(tenantA, knowledgeBaseA, foreign.documentId()))
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .header("Idempotency-Key", "platform-delete"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete(endpoint(tenantA, knowledgeBaseA, foreign.documentId()))
                        .header("Authorization", "Bearer dq_app_key.secret")
                        .header("Idempotency-Key", "app-delete"))
                .andExpect(status().isForbidden());
    }

    @Test
    void finalRetryCreatesNewAttemptAndHonorsRetryability() throws Exception {
        Graph retryable = insertReadyDocument(tenantA, knowledgeBaseA, adminA, "Retry Delete");
        Graph permanent = insertReadyDocument(tenantA, knowledgeBaseA, adminA, "Permanent Delete");
        AuthSession admin = login("deletion-a.admin");
        long retryableJob = acceptAndFail(admin, retryable, "initial-retryable", true);
        acceptAndFail(admin, permanent, "initial-permanent", false);

        String retryEndpoint = endpoint(
                tenantA, knowledgeBaseA, retryable.documentId()
        ) + "/deletion/retry";
        MvcResult accepted = mockMvc.perform(post(retryEndpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "manual-delete-retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.jobStatus").value("1"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        long newJob = JsonPath.<Number>read(
                accepted.getResponse().getContentAsString(), "$.deletionJobId"
        ).longValue();
        assertThat(newJob).isNotEqualTo(retryableJob);

        mockMvc.perform(post(retryEndpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "manual-delete-retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deletionJobId").value(newJob))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post(endpoint(
                                tenantA, knowledgeBaseA, permanent.documentId())
                                + "/deletion/retry")
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "manual-permanent-retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_DELETION_NOT_RETRYABLE"));
    }

    @Test
    void alreadyDeletedReturnsNoContentAndReleasesName() throws Exception {
        Graph graph = insertReadyDocument(tenantA, knowledgeBaseA, adminA, "Reusable Guide");
        jdbcTemplate.update("""
                UPDATE document
                SET status='3', active_name=NULL, active_version_id=NULL,
                    deletion_requested_at=?, deleted_at=?, updated_at=?
                WHERE id=?
                """, now(), now(), now(), graph.documentId());
        AuthSession admin = login("deletion-a.admin");

        mockMvc.perform(delete(endpoint(tenantA, knowledgeBaseA, graph.documentId()))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "delete-tombstone"))
                .andExpect(status().isNoContent());

        long replacement = insertDocument(
                tenantA, knowledgeBaseA, adminA, "Reusable Guide"
        );
        assertThat(replacement).isNotEqualTo(graph.documentId());
    }

    @Test
    void concurrentDeletionCreatesOnlyOneAttempt() throws Exception {
        Graph graph = insertReadyDocument(
                tenantA, knowledgeBaseA, adminA, "Concurrent Delete"
        );
        AdminPrincipal principal = new AdminPrincipal(
                adminA, tenantA, "deletion-a.admin", null, "2", true
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Object>> calls = List.of(
                    () -> deleteAtBarrier(barrier, principal, graph.documentId(), "delete-a"),
                    () -> deleteAtBarrier(barrier, principal, graph.documentId(), "delete-b")
            );
            List<Future<Object>> futures = pool.invokeAll(calls);
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            assertThat(results.stream()
                    .filter(DocumentDeletionAcceptedVO.class::isInstance).count()).isOne();
            assertThat(results.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .map(BusinessException::code)
                    .toList()).containsExactly("DOCUMENT_DELETION_IN_PROGRESS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(value("SELECT COUNT(*) FROM document_deletion_job WHERE document_id=?",
                Integer.class, graph.documentId())).isOne();
        assertThat(value("SELECT COUNT(*) FROM outbox_event WHERE document_id=? AND event_type='2'",
                Integer.class, graph.documentId())).isOne();
    }

    private Object deleteAtBarrier(
            CyclicBarrier barrier,
            AdminPrincipal principal,
            long documentId,
            String key
    ) throws Exception {
        barrier.await();
        try {
            return deletionService.deleteDocument(
                    principal, tenantA, knowledgeBaseA, documentId, key
            );
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private long acceptAndFail(
            AuthSession admin,
            Graph graph,
            String key,
            boolean retryable
    ) throws Exception {
        MvcResult result = mockMvc.perform(delete(endpoint(
                                tenantA, knowledgeBaseA, graph.documentId()))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andReturn();
        long jobId = JsonPath.<Number>read(
                result.getResponse().getContentAsString(), "$.deletionJobId"
        ).longValue();
        jdbcTemplate.update("""
                UPDATE document_deletion_job
                SET status='4', failure_code='DELETE_TEST_FAILURE',
                    failure_message='safe deletion failure', failure_retryable=?,
                    finished_at=?, updated_at=?
                WHERE id=?
                """, retryable, now(), now(), jobId);
        return jobId;
    }

    private Graph insertReadyDocument(
            long tenantId,
            long knowledgeBaseId,
            long adminId,
            String name
    ) {
        long documentId = insertDocument(tenantId, knowledgeBaseId, adminId, name);
        long versionId = insertVersion(tenantId, documentId, adminId, "2");
        long jobId = insertJob(tenantId, versionId, "3");
        jdbcTemplate.update("""
                UPDATE document SET active_version_id=?, latest_version_id=?, updated_at=?
                WHERE id=?
                """, versionId, versionId, now(), documentId);
        return new Graph(documentId, versionId, jobId);
    }

    private Graph insertProcessingDocument(
            long tenantId,
            long knowledgeBaseId,
            long adminId,
            String name
    ) {
        long documentId = insertDocument(tenantId, knowledgeBaseId, adminId, name);
        long versionId = insertVersion(tenantId, documentId, adminId, "1");
        long jobId = insertJob(tenantId, versionId, "1");
        jdbcTemplate.update("""
                UPDATE document SET latest_version_id=?, updated_at=? WHERE id=?
                """, versionId, now(), documentId);
        return new Graph(documentId, versionId, jobId);
    }

    private long insertDocument(
            long tenantId,
            long knowledgeBaseId,
            long adminId,
            String name
    ) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO document (
                    tenant_id, knowledge_base_id, name, active_name, status,
                    active_version_id, latest_version_id, created_by_admin_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, '1', NULL, NULL, ?, ?, ?)
                """, tenantId, knowledgeBaseId, name, name, adminId, now, now);
        return value("""
                SELECT id FROM document
                WHERE tenant_id=? AND knowledge_base_id=? AND active_name=?
                """, Long.class, tenantId, knowledgeBaseId, name);
    }

    private long insertVersion(long tenantId, long documentId, long adminId, String status) {
        LocalDateTime now = now();
        String hash = "%064x".formatted(documentId);
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    tenant_id, document_id, version_no, status, source_format,
                    original_filename, source_bucket, source_object_key,
                    source_size_bytes, source_sha256, source_content_type,
                    idempotency_key_hash, request_fingerprint, accepted_by_admin_id,
                    ready_at, created_at, updated_at
                ) VALUES (?, ?, 1, ?, '4', 'deletion.md', 'docquery-source', ?,
                          10, ?, 'text/markdown', ?, ?, ?, ?, ?, ?)
                """,
                tenantId, documentId, status, "source/deletion/" + documentId,
                hash, hash, hash, adminId, "2".equals(status) ? now : null, now, now
        );
        return value("SELECT id FROM document_version WHERE document_id=?",
                Long.class, documentId);
    }

    private long insertJob(long tenantId, long versionId, String status) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO processing_job (
                    tenant_id, document_version_id, job_type, attempt_no, status,
                    started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, '1', 1, ?, ?, ?, ?, ?)
                """, tenantId, versionId, status, now,
                "1".equals(status) ? null : now, now, now);
        return value("SELECT id FROM processing_job WHERE document_version_id=?",
                Long.class, versionId);
    }

    private AuthSession login(String loginName) throws Exception {
        CsrfSession csrf = fetchCsrf(null);
        mockMvc.perform(post("/api/admin/v1/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginName":"%s","password":"%s"}
                                """.formatted(loginName, PASSWORD)))
                .andExpect(status().isOk());
        CsrfSession authenticated = fetchCsrf(csrf.session());
        return new AuthSession(
                authenticated.session(), authenticated.headerName(), authenticated.token()
        );
    }

    private CsrfSession fetchCsrf(MockHttpSession existingSession) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/v1/auth/csrf");
        if (existingSession != null) {
            request.session(existingSession);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new CsrfSession(
                (MockHttpSession) result.getRequest().getSession(false),
                JsonPath.read(body, "$.headerName"),
                JsonPath.read(body, "$.token")
        );
    }

    private long insertTenant(String name) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO tenant (name, status, created_at, updated_at)
                VALUES (?, '1', ?, ?)
                """, name, now, now);
        return value("SELECT id FROM tenant WHERE name=?", Long.class, name);
    }

    private long insertAdmin(Long tenantId, String loginName, String role) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, '1', ?, ?)
                """, tenantId, loginName, passwordEncoder.encode(PASSWORD), role, now, now);
        return value("SELECT id FROM admin_user WHERE login_name=?", Long.class, loginName);
    }

    private long insertKnowledgeBase(long tenantId, String name, String status) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, ?, ?)
                """, tenantId, name, status, now, now);
        return value("""
                SELECT id FROM knowledge_base WHERE tenant_id=? AND name=?
                """, Long.class, tenantId, name);
    }

    private String endpoint(long tenantId, long knowledgeBaseId, long documentId) {
        return "/api/admin/v1/tenants/" + tenantId
                + "/knowledge-bases/" + knowledgeBaseId
                + "/documents/" + documentId;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private record Graph(long documentId, long versionId, long processingJobId) {
    }

    private record CsrfSession(
            MockHttpSession session,
            String headerName,
            String token
    ) {
    }

    private record AuthSession(
            MockHttpSession session,
            String csrfHeader,
            String csrfToken
    ) {
    }
}
