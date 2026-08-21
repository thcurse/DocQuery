package com.doc.docquery;

import com.jayway.jsonpath.JsonPath;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentManagementService;
import com.doc.docquery.vo.ProcessingJobRetryAcceptedVO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** N4.1 管理契约、租户隔离、人工重试幂等和并发验收。 */
@Testcontainers
@SpringBootTest(properties = {
        "docquery.object-storage.enabled=false",
        "docquery.messaging.infrastructure-enabled=false",
        "docquery.messaging.publisher-enabled=false",
        "docquery.messaging.listener-enabled=false"
})
@AutoConfigureMockMvc
class DocumentManagementIT {

    private static final String CSRF_ENDPOINT = "/api/admin/v1/auth/csrf";
    private static final String LOGIN_ENDPOINT = "/api/admin/v1/auth/login";
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
    private DocumentManagementService managementService;

    private long tenantAId;
    private long tenantBId;
    private long adminAId;
    private long knowledgeBaseAId;
    private long knowledgeBaseBId;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM document_search_projection");
        jdbcTemplate.update("DELETE FROM document_retrieval_artifact");
        jdbcTemplate.update("DELETE FROM document_canonical_artifact");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");

        tenantAId = insertTenant("Management Tenant A");
        tenantBId = insertTenant("Management Tenant B");
        adminAId = insertAdmin(tenantAId, "management-a.admin", "2");
        insertAdmin(tenantBId, "management-b.admin", "2");
        insertAdmin(null, "management.platform", "1");
        knowledgeBaseAId = insertKnowledgeBase(tenantAId, "Management KB A", "1");
        knowledgeBaseBId = insertKnowledgeBase(tenantBId, "Management KB B", "1");
    }

    @Test
    void documentVersionAndJobQueriesExposeActiveLatestDifferenceWithoutSecrets()
            throws Exception {
        Graph graph = insertReadyThenFailedGraph(
                tenantAId,
                knowledgeBaseAId,
                adminAId,
                "Manual Guide",
                true
        );
        AuthSession admin = login("management-a.admin");
        String documents = documentsEndpoint(tenantAId, knowledgeBaseAId);

        MvcResult list = mockMvc.perform(get(documents)
                        .session(admin.session())
                        .param("name", "Manual")
                        .param("latestVersionStatus", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].documentId").value(graph.documentId()))
                .andExpect(jsonPath("$.items[0].activeVersion.documentVersionId")
                        .value(graph.activeVersionId()))
                .andExpect(jsonPath("$.items[0].activeVersion.status").value("2"))
                .andExpect(jsonPath("$.items[0].latestVersion.documentVersionId")
                        .value(graph.latestVersionId()))
                .andExpect(jsonPath("$.items[0].latestVersion.status").value("3"))
                .andExpect(jsonPath("$.items[0].latestVersion.failureRetryable").value(true))
                .andReturn();
        assertThat(list.getResponse().getContentAsString())
                .doesNotContain("sourceBucket")
                .doesNotContain("sourceObjectKey")
                .doesNotContain("sourceSha256")
                .doesNotContain("idempotencyKeyHash")
                .doesNotContain("requestFingerprint")
                .doesNotContain("leaseOwner");

        mockMvc.perform(get(documents + "/" + graph.documentId() + "/versions")
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].documentVersionId")
                        .value(graph.latestVersionId()))
                .andExpect(jsonPath("$.items[0].active").value(false))
                .andExpect(jsonPath("$.items[0].latestProcessingJob.processingJobId")
                        .value(graph.failedJobId()))
                .andExpect(jsonPath("$.items[1].documentVersionId")
                        .value(graph.activeVersionId()))
                .andExpect(jsonPath("$.items[1].active").value(true));

        String jobs = jobsEndpoint(tenantAId);
        mockMvc.perform(get(jobs)
                        .session(admin.session())
                        .param("knowledgeBaseId", Long.toString(knowledgeBaseAId))
                        .param("status", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].processingJobId")
                        .value(graph.failedJobId()))
                .andExpect(jsonPath("$.items[0].documentName").value("Manual Guide"));

        mockMvc.perform(get(jobs + "/" + graph.failedJobId())
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.processingJobId").value(graph.failedJobId()))
                .andExpect(jsonPath("$.attemptHistory.length()").value(1))
                .andExpect(jsonPath("$.attemptHistory[0].failureRetryable").value(true));
    }

    @Test
    void manualRetryIsAtomicDurableAndIdempotent() throws Exception {
        Graph first = insertReadyThenFailedGraph(
                tenantAId, knowledgeBaseAId, adminAId, "Retry Guide", true
        );
        Graph second = insertReadyThenFailedGraph(
                tenantAId, knowledgeBaseAId, adminAId, "Other Guide", true
        );
        AuthSession admin = login("management-a.admin");
        String endpoint = jobsEndpoint(tenantAId) + "/" + first.failedJobId() + "/retry";

        mockMvc.perform(post(endpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        MvcResult accepted = mockMvc.perform(post(endpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "retry-command-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceProcessingJobId").value(first.failedJobId()))
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.versionStatus").value("1"))
                .andExpect(jsonPath("$.jobStatus").value("1"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        long retryJobId = JsonPath.<Number>read(
                accepted.getResponse().getContentAsString(),
                "$.processingJobId"
        ).longValue();

        mockMvc.perform(post(endpoint)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "retry-command-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processingJobId").value(retryJobId))
                .andExpect(jsonPath("$.replayed").value(true));

        assertThat(value("SELECT status FROM document_version WHERE id=?", String.class,
                first.latestVersionId())).isEqualTo("1");
        assertThat(value("SELECT active_version_id FROM document WHERE id=?", Long.class,
                first.documentId())).isEqualTo(first.activeVersionId());
        assertThat(value("SELECT COUNT(*) FROM processing_job WHERE document_version_id=?",
                Integer.class, first.latestVersionId())).isEqualTo(2);
        assertThat(value("SELECT COUNT(*) FROM outbox_event WHERE processing_job_id=?",
                Integer.class, retryJobId)).isOne();

        mockMvc.perform(post(jobsEndpoint(tenantAId) + "/" + second.failedJobId() + "/retry")
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", "retry-command-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void retryRejectsNonRetryableSupersededAndDisabledKnowledgeBase() throws Exception {
        Graph nonRetryable = insertReadyThenFailedGraph(
                tenantAId, knowledgeBaseAId, adminAId, "Permanent Failure", false
        );
        Graph superseded = insertTwoFailedVersions(
                tenantAId, knowledgeBaseAId, adminAId, "Superseded Failure"
        );
        long disabledKnowledgeBase = insertKnowledgeBase(
                tenantAId, "Disabled Management KB", "2"
        );
        Graph disabled = insertReadyThenFailedGraph(
                tenantAId, disabledKnowledgeBase, adminAId, "Disabled Failure", true
        );
        AuthSession admin = login("management-a.admin");

        assertRetryConflict(admin, nonRetryable.failedJobId(), "retry-permanent",
                "PROCESSING_JOB_NOT_RETRYABLE");
        assertRetryConflict(admin, superseded.activeJobId(), "retry-superseded-version",
                "DOCUMENT_VERSION_SUPERSEDED");
        assertRetryConflict(admin, disabled.failedJobId(), "retry-disabled-kb",
                "KNOWLEDGE_BASE_NOT_ACTIVE");

        jdbcTemplate.update("""
                INSERT INTO processing_job (
                    tenant_id, document_version_id, job_type, attempt_no, status,
                    failure_code, failure_message, failure_retryable,
                    started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, '1', 2, '4', 'LATEST_FAILURE', 'latest failure', 1,
                          ?, ?, ?, ?)
                """,
                tenantAId,
                nonRetryable.latestVersionId(),
                now(), now(), now(), now()
        );
        assertRetryConflict(admin, nonRetryable.failedJobId(), "retry-old-attempt",
                "PROCESSING_JOB_SUPERSEDED");
    }

    @Test
    void managementEndpointsEnforceTenantRoleAndHideForeignResources() throws Exception {
        Graph foreign = insertReadyThenFailedGraph(
                tenantBId,
                knowledgeBaseBId,
                value("SELECT id FROM admin_user WHERE login_name='management-b.admin'",
                        Long.class),
                "Foreign Guide",
                true
        );
        AuthSession adminA = login("management-a.admin");
        AuthSession platform = login("management.platform");

        mockMvc.perform(get(documentsEndpoint(tenantBId, knowledgeBaseBId))
                        .session(adminA.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));

        mockMvc.perform(get(documentsEndpoint(tenantAId, knowledgeBaseAId)
                        + "/" + foreign.documentId()).session(adminA.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(get(jobsEndpoint(tenantAId) + "/" + foreign.failedJobId())
                        .session(adminA.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROCESSING_JOB_NOT_FOUND"));

        mockMvc.perform(get(jobsEndpoint(tenantAId)).session(platform.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get(jobsEndpoint(tenantAId))
                        .header("Authorization", "Bearer dq_app_key.secret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void concurrentManualRetryCreatesOnlyOneAttemptAndOutbox() throws Exception {
        Graph graph = insertReadyThenFailedGraph(
                tenantAId, knowledgeBaseAId, adminAId, "Concurrent Retry", true
        );
        AdminPrincipal principal = new AdminPrincipal(
                adminAId, tenantAId, "management-a.admin", null, "2", true
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Object>> calls = List.of(
                    () -> retryAtBarrier(barrier, principal, graph.failedJobId(), "concurrent-a"),
                    () -> retryAtBarrier(barrier, principal, graph.failedJobId(), "concurrent-b")
            );
            List<Future<Object>> futures = pool.invokeAll(calls);
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            assertThat(results.stream()
                    .filter(ProcessingJobRetryAcceptedVO.class::isInstance)
                    .count()).isOne();
            assertThat(results.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .map(BusinessException::code)
                    .toList()).allMatch(code -> code.equals("RETRY_ALREADY_IN_PROGRESS")
                    || code.equals("PROCESSING_JOB_SUPERSEDED"));
        } finally {
            pool.shutdownNow();
        }

        assertThat(value("SELECT COUNT(*) FROM processing_job WHERE document_version_id=?",
                Integer.class, graph.latestVersionId())).isEqualTo(2);
        assertThat(value("SELECT COUNT(*) FROM outbox_event WHERE document_version_id=?",
                Integer.class, graph.latestVersionId())).isOne();
    }

    private Object retryAtBarrier(
            CyclicBarrier barrier,
            AdminPrincipal principal,
            long jobId,
            String key
    ) throws Exception {
        barrier.await();
        try {
            return managementService.retryProcessingJob(
                    principal,
                    tenantAId,
                    jobId,
                    key
            );
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private void assertRetryConflict(
            AuthSession admin,
            long jobId,
            String key,
            String expectedCode
    ) throws Exception {
        mockMvc.perform(post(jobsEndpoint(tenantAId) + "/" + jobId + "/retry")
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private Graph insertReadyThenFailedGraph(
            long tenantId,
            long knowledgeBaseId,
            long adminId,
            String name,
            boolean retryable
    ) {
        long documentId = insertDocument(tenantId, knowledgeBaseId, adminId, name);
        long readyVersion = insertVersion(tenantId, documentId, adminId, 1, "2", null);
        long readyJob = insertJob(tenantId, readyVersion, 1, "3", null, null);
        long failedVersion = insertVersion(
                tenantId, documentId, adminId, 2, "3", "TEST_FAILURE"
        );
        long failedJob = insertJob(
                tenantId,
                failedVersion,
                1,
                "4",
                "TEST_FAILURE",
                retryable
        );
        jdbcTemplate.update("""
                UPDATE document SET active_version_id=?, latest_version_id=?, updated_at=?
                WHERE id=?
                """, readyVersion, failedVersion, now(), documentId);
        return new Graph(documentId, readyVersion, failedVersion, readyJob, failedJob);
    }

    private Graph insertTwoFailedVersions(
            long tenantId,
            long knowledgeBaseId,
            long adminId,
            String name
    ) {
        long documentId = insertDocument(tenantId, knowledgeBaseId, adminId, name);
        long first = insertVersion(
                tenantId, documentId, adminId, 1, "3", "OLD_FAILURE"
        );
        long firstJob = insertJob(tenantId, first, 1, "4", "OLD_FAILURE", true);
        long second = insertVersion(
                tenantId, documentId, adminId, 2, "3", "NEW_FAILURE"
        );
        long secondJob = insertJob(tenantId, second, 1, "4", "NEW_FAILURE", true);
        jdbcTemplate.update("""
                UPDATE document SET active_version_id=NULL, latest_version_id=?, updated_at=?
                WHERE id=?
                """, second, now(), documentId);
        return new Graph(documentId, first, second, firstJob, secondJob);
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
                    tenant_id, knowledge_base_id, name, status,
                    active_version_id, latest_version_id, created_by_admin_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, '1', NULL, NULL, ?, ?, ?)
                """, tenantId, knowledgeBaseId, name, adminId, now, now);
        return value("""
                SELECT id FROM document
                WHERE tenant_id=? AND knowledge_base_id=? AND name=?
                """, Long.class, tenantId, knowledgeBaseId, name);
    }

    private long insertVersion(
            long tenantId,
            long documentId,
            long adminId,
            int versionNo,
            String status,
            String failureCode
    ) {
        LocalDateTime now = now();
        String hash = "%064x".formatted(documentId * 100L + versionNo);
        String objectKey = "source/management/" + documentId + "/" + versionNo;
        jdbcTemplate.update("""
                INSERT INTO document_version (
                    tenant_id, document_id, version_no, status, source_format,
                    original_filename, source_bucket, source_object_key,
                    source_size_bytes, source_sha256, source_content_type,
                    idempotency_key_hash, request_fingerprint, accepted_by_admin_id,
                    failure_code, failure_message, ready_at, failed_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, '4', ?, 'docquery-source', ?,
                          10, ?, 'text/markdown', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                documentId,
                versionNo,
                status,
                "management-v" + versionNo + ".md",
                objectKey,
                hash,
                hash,
                hash,
                adminId,
                failureCode,
                failureCode == null ? null : "safe failure",
                "2".equals(status) ? now : null,
                "3".equals(status) ? now : null,
                now,
                now
        );
        return value("""
                SELECT id FROM document_version
                WHERE document_id=? AND version_no=?
                """, Long.class, documentId, versionNo);
    }

    private long insertJob(
            long tenantId,
            long versionId,
            int attemptNo,
            String status,
            String failureCode,
            Boolean retryable
    ) {
        LocalDateTime now = now();
        jdbcTemplate.update("""
                INSERT INTO processing_job (
                    tenant_id, document_version_id, job_type, attempt_no, status,
                    failure_code, failure_message, failure_retryable,
                    started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, '1', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                versionId,
                attemptNo,
                status,
                failureCode,
                failureCode == null ? null : "safe failure",
                retryable,
                now,
                now,
                now,
                now
        );
        return value("""
                SELECT id FROM processing_job
                WHERE document_version_id=? AND attempt_no=?
                """, Long.class, versionId, attemptNo);
    }

    private AuthSession login(String loginName) throws Exception {
        CsrfSession csrf = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginName":"%s","password":"%s"}
                                """.formatted(loginName, PASSWORD)))
                .andExpect(status().isOk());
        CsrfSession authenticated = fetchCsrf(csrf.session());
        return new AuthSession(
                authenticated.session(),
                authenticated.headerName(),
                authenticated.token()
        );
    }

    private CsrfSession fetchCsrf(MockHttpSession existingSession) throws Exception {
        MockHttpServletRequestBuilder request = get(CSRF_ENDPOINT);
        if (existingSession != null) {
            request.session(existingSession);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        String body = result.getResponse().getContentAsString();
        return new CsrfSession(
                session,
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

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private <T> T value(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, type, arguments);
    }

    private String documentsEndpoint(long tenantId, long knowledgeBaseId) {
        return "/api/admin/v1/tenants/" + tenantId
                + "/knowledge-bases/" + knowledgeBaseId + "/documents";
    }

    private String jobsEndpoint(long tenantId) {
        return "/api/admin/v1/tenants/" + tenantId + "/processing-jobs";
    }

    private record Graph(
            long documentId,
            long activeVersionId,
            long latestVersionId,
            long activeJobId,
            long failedJobId
    ) {
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
