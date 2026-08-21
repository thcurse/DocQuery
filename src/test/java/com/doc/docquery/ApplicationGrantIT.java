package com.doc.docquery;

import com.doc.docquery.dto.CreateCredentialDTO;
import com.doc.docquery.dto.UpsertApplicationGrantDTO;
import com.doc.docquery.enums.GrantPermission;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.security.KnowledgeBaseAccessContext;
import com.doc.docquery.security.KnowledgeBaseAccessDeniedException;
import com.doc.docquery.service.ApplicationCredentialResolver;
import com.doc.docquery.service.ApplicationGrantService;
import com.doc.docquery.service.CredentialService;
import com.doc.docquery.service.KnowledgeBaseAccessAuthorizer;
import com.doc.docquery.vo.CreatedCredentialVO;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
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
import java.util.Map;

import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.APPLICATION_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.CREDENTIAL_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.GRANT_MISSING;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.GRANT_REVOKED;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.INSUFFICIENT_PERMISSION;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.KNOWLEDGE_BASE_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.TENANT_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.TENANT_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
class ApplicationGrantIT {

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
    private ApplicationGrantService applicationGrantService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private ApplicationCredentialResolver credentialResolver;

    @Autowired
    private KnowledgeBaseAccessAuthorizer accessAuthorizer;

    private long tenantAId;
    private long tenantBId;
    private long adminAId;
    private long applicationAId;
    private long applicationA2Id;
    private long applicationBId;
    private long knowledgeBaseAId;
    private long knowledgeBaseA2Id;
    private long knowledgeBaseBId;
    private final Map<Long, String> createdCredentials = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        createdCredentials.clear();
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");

        tenantAId = insertTenant("Grant Tenant A");
        tenantBId = insertTenant("Grant Tenant B");
        adminAId = insertAdmin(tenantAId, "grant-a.admin", "2");
        insertAdmin(tenantBId, "grant-b.admin", "2");
        insertAdmin(null, "grant.platform", "1");
        applicationAId = insertApplication(tenantAId, "grant-app-a", "Grant App A");
        applicationA2Id = insertApplication(tenantAId, "grant-app-a2", "Grant App A2");
        applicationBId = insertApplication(tenantBId, "grant-app-b", "Grant App B");
        knowledgeBaseAId = insertKnowledgeBase(tenantAId, "Grant Knowledge A");
        knowledgeBaseA2Id = insertKnowledgeBase(tenantAId, "Grant Knowledge A2");
        knowledgeBaseBId = insertKnowledgeBase(tenantBId, "Grant Knowledge B");
    }

    @Test
    void grantCrudIsIdempotentAndListsFromBothDirections() throws Exception {
        AuthSession admin = login("grant-a.admin");
        MvcResult created = putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("1"))
                .andExpect(jsonPath("$.status").value("1"))
                .andExpect(jsonPath("$.grantedBy").value(adminAId))
                .andExpect(jsonPath("$.grantedAt").value(endsWith("Z")))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        long grantId = JsonPath.<Number>read(body, "$.id").longValue();
        String grantedAt = JsonPath.read(body, "$.grantedAt");

        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(grantId))
                .andExpect(jsonPath("$.grantedAt").value(grantedAt));
        assertThat(countGrants(applicationAId, knowledgeBaseAId)).isEqualTo(1);

        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(grantId))
                .andExpect(jsonPath("$.permission").value("3"));

        mockMvc.perform(get(applicationGrantsEndpoint(tenantAId, applicationAId))
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].knowledgeBaseId").value(knowledgeBaseAId))
                .andExpect(jsonPath("$.items[0].permission").value("3"));
        mockMvc.perform(get(knowledgeBaseGrantsEndpoint(tenantAId, knowledgeBaseAId))
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].applicationId").value(applicationAId));

        mockMvc.perform(delete(grantEndpoint(tenantAId, applicationAId, knowledgeBaseAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());
        Map<String, Object> revoked = storedGrant(applicationAId, knowledgeBaseAId);
        assertThat(revoked.get("status")).isEqualTo("3");

        mockMvc.perform(delete(grantEndpoint(tenantAId, applicationAId, knowledgeBaseAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());
        assertThat(storedGrant(applicationAId, knowledgeBaseAId)).isEqualTo(revoked);

        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(grantId))
                .andExpect(jsonPath("$.permission").value("2"))
                .andExpect(jsonPath("$.status").value("1"))
                .andExpect(jsonPath("$.revokedAt").doesNotExist())
                .andExpect(jsonPath("$.revokedBy").doesNotExist());
    }

    @Test
    void readWriteAndCombinedPermissionsAreAuthorizedExplicitly() {
        AdminPrincipal admin = adminPrincipal();
        ApplicationCredentialPrincipal appA = createCredentialPrincipal(
                admin,
                applicationAId,
                "Grant credential A"
        );
        ApplicationCredentialPrincipal appA2 = createCredentialPrincipal(
                admin,
                applicationA2Id,
                "Grant credential A2"
        );

        applicationGrantService.upsertGrant(
                admin,
                tenantAId,
                applicationAId,
                knowledgeBaseAId,
                grantDto("1")
        );
        KnowledgeBaseAccessContext read = accessAuthorizer.authorize(
                appA,
                knowledgeBaseAId,
                GrantPermission.READ
        );
        assertThat(read.getGrantedPermission()).isEqualTo("1");
        assertDenied(appA, knowledgeBaseAId, GrantPermission.WRITE, INSUFFICIENT_PERMISSION);

        applicationGrantService.upsertGrant(
                admin,
                tenantAId,
                applicationAId,
                knowledgeBaseAId,
                grantDto("2")
        );
        accessAuthorizer.authorize(appA, knowledgeBaseAId, GrantPermission.WRITE);
        assertDenied(appA, knowledgeBaseAId, GrantPermission.READ, INSUFFICIENT_PERMISSION);

        applicationGrantService.upsertGrant(
                admin,
                tenantAId,
                applicationAId,
                knowledgeBaseAId,
                grantDto("3")
        );
        accessAuthorizer.authorize(appA, knowledgeBaseAId, GrantPermission.READ);
        accessAuthorizer.authorize(appA, knowledgeBaseAId, GrantPermission.WRITE);
        accessAuthorizer.authorize(appA, knowledgeBaseAId, GrantPermission.READ_WRITE);

        assertDenied(appA2, knowledgeBaseAId, GrantPermission.READ, GRANT_MISSING);
    }

    @Test
    void crossTenantGrantAndAdministratorEscalationAreRejectedWithoutInsert()
            throws Exception {
        AuthSession adminA = login("grant-a.admin");
        putGrant(adminA, tenantAId, applicationAId, knowledgeBaseBId, "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
        putGrant(adminA, tenantAId, applicationBId, knowledgeBaseAId, "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        putGrant(adminA, tenantBId, applicationBId, knowledgeBaseBId, "1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));

        AuthSession platform = login("grant.platform");
        putGrant(platform, tenantAId, applicationAId, knowledgeBaseAId, "1")
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_grant",
                Integer.class
        )).isZero();

        ApplicationCredentialPrincipal appA = createCredentialPrincipal(
                adminPrincipal(),
                applicationAId,
                "Cross-tenant credential"
        );
        assertDenied(appA, knowledgeBaseBId, GrantPermission.READ, TENANT_MISMATCH);
    }

    @Test
    void revokedOrDisabledScopeImmediatelyDeniesNewAuthorization() {
        AdminPrincipal admin = adminPrincipal();
        ApplicationCredentialPrincipal appA = createCredentialPrincipal(
                admin,
                applicationAId,
                "Immediate invalidation credential"
        );
        applicationGrantService.upsertGrant(
                admin,
                tenantAId,
                applicationAId,
                knowledgeBaseAId,
                grantDto("1")
        );
        accessAuthorizer.authorize(appA, knowledgeBaseAId, GrantPermission.READ);

        applicationGrantService.revokeGrant(
                admin,
                tenantAId,
                applicationAId,
                knowledgeBaseAId
        );
        assertDenied(appA, knowledgeBaseAId, GrantPermission.READ, GRANT_REVOKED);

        applicationGrantService.upsertGrant(
                admin,
                tenantAId,
                applicationAId,
                knowledgeBaseAId,
                grantDto("1")
        );
        jdbcTemplate.update(
                "UPDATE knowledge_base SET status = '2' WHERE id = ?",
                knowledgeBaseAId
        );
        assertDenied(
                appA,
                knowledgeBaseAId,
                GrantPermission.READ,
                KNOWLEDGE_BASE_UNAVAILABLE
        );
        jdbcTemplate.update(
                "UPDATE knowledge_base SET status = '1' WHERE id = ?",
                knowledgeBaseAId
        );

        jdbcTemplate.update("UPDATE application SET status = '2' WHERE id = ?", applicationAId);
        assertDenied(appA, knowledgeBaseAId, GrantPermission.READ, APPLICATION_UNAVAILABLE);
        jdbcTemplate.update("UPDATE application SET status = '1' WHERE id = ?", applicationAId);

        jdbcTemplate.update("UPDATE tenant SET status = '2' WHERE id = ?", tenantAId);
        assertDenied(appA, knowledgeBaseAId, GrantPermission.READ, TENANT_UNAVAILABLE);
        jdbcTemplate.update("UPDATE tenant SET status = '1' WHERE id = ?", tenantAId);

        jdbcTemplate.update(
                "UPDATE credential SET status = '3' WHERE id = ?",
                appA.getCredentialId()
        );
        assertDenied(appA, knowledgeBaseAId, GrantPermission.READ, CREDENTIAL_UNAVAILABLE);
    }

    @Test
    void inactiveResourcesCannotReceiveGrantButExistingGrantCanBeListedAndRevoked()
            throws Exception {
        AuthSession admin = login("grant-a.admin");
        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "1")
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE application SET status = '2' WHERE id = ?", applicationAId);
        putGrant(admin, tenantAId, applicationAId, knowledgeBaseA2Id, "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ACTIVE"));
        mockMvc.perform(get(applicationGrantsEndpoint(tenantAId, applicationAId))
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(delete(grantEndpoint(tenantAId, applicationAId, knowledgeBaseAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());

        jdbcTemplate.update("UPDATE application SET status = '1' WHERE id = ?", applicationAId);
        jdbcTemplate.update(
                "UPDATE knowledge_base SET status = '2' WHERE id = ?",
                knowledgeBaseA2Id
        );
        putGrant(admin, tenantAId, applicationAId, knowledgeBaseA2Id, "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_ACTIVE"));
    }

    @Test
    void permissionValidationPaginationCsrfAndApplicationCredentialBoundaryAreStable()
            throws Exception {
        AuthSession admin = login("grant-a.admin");
        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "4")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "")
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(grantEndpoint(tenantAId, applicationAId, knowledgeBaseAId))
                        .session(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission\":\"1\"}"))
                .andExpect(status().isForbidden());

        putGrant(admin, tenantAId, applicationAId, knowledgeBaseAId, "1")
                .andExpect(status().isOk());
        putGrant(admin, tenantAId, applicationAId, knowledgeBaseA2Id, "2")
                .andExpect(status().isOk());
        mockMvc.perform(get(applicationGrantsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get(applicationGrantsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .param("size", "101"))
                .andExpect(status().isBadRequest());

        ApplicationCredentialPrincipal appA = createCredentialPrincipal(
                adminPrincipal(),
                applicationAId,
                "Management boundary credential"
        );
        String fullCredential = credentialValue(appA.getCredentialId());
        mockMvc.perform(get(applicationGrantsEndpoint(tenantAId, applicationAId))
                        .header("Authorization", "Bearer " + fullCredential))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions putGrant(
            AuthSession admin,
            long tenantId,
            long applicationId,
            long knowledgeBaseId,
            String permission
    ) throws Exception {
        return mockMvc.perform(put(grantEndpoint(tenantId, applicationId, knowledgeBaseId))
                .session(admin.session())
                .header(admin.csrfHeader(), admin.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permission\":\"" + permission + "\"}"));
    }

    private ApplicationCredentialPrincipal createCredentialPrincipal(
            AdminPrincipal admin,
            long applicationId,
            String name
    ) {
        CreateCredentialDTO dto = new CreateCredentialDTO();
        dto.setName(name);
        CreatedCredentialVO created = credentialService.createCredential(
                admin,
                tenantAId,
                applicationId,
                dto
        );
        ApplicationCredentialPrincipal principal = credentialResolver.resolve(
                created.getCredential()
        );
        createdCredentials.put(principal.getCredentialId(), created.getCredential());
        return principal;
    }

    private String credentialValue(long credentialId) {
        return createdCredentials.get(credentialId);
    }

    private UpsertApplicationGrantDTO grantDto(String permission) {
        UpsertApplicationGrantDTO dto = new UpsertApplicationGrantDTO();
        dto.setPermission(permission);
        return dto;
    }

    private AdminPrincipal adminPrincipal() {
        return new AdminPrincipal(
                adminAId,
                tenantAId,
                "grant-a.admin",
                null,
                "2",
                true
        );
    }

    private void assertDenied(
            ApplicationCredentialPrincipal principal,
            long knowledgeBaseId,
            GrantPermission requiredPermission,
            KnowledgeBaseAccessDeniedException.Reason reason
    ) {
        KnowledgeBaseAccessDeniedException exception = catchThrowableOfType(
                KnowledgeBaseAccessDeniedException.class,
                () -> accessAuthorizer.authorize(
                        principal,
                        knowledgeBaseId,
                        requiredPermission
                )
        );
        assertThat(exception).isNotNull();
        assertThat(exception.reason()).isEqualTo(reason);
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
        return jdbcTemplate.queryForObject(
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
                ) VALUES (?, ?, ?, ?, '1', ?, ?)
                """,
                tenantId,
                loginName,
                passwordEncoder.encode(PASSWORD),
                role,
                now,
                now
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM admin_user WHERE login_name = ?",
                Long.class,
                loginName
        );
    }

    private long insertApplication(long tenantId, String code, String name) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO application (
                    tenant_id, code, name, environment, description, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'PRODUCTION', NULL, '1', ?, ?)
                """,
                tenantId,
                code,
                name,
                now,
                now
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM application WHERE tenant_id = ? AND code = ?",
                Long.class,
                tenantId,
                code
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
        return jdbcTemplate.queryForObject(
                """
                SELECT id FROM knowledge_base
                WHERE tenant_id = ? AND name = ?
                ORDER BY id DESC LIMIT 1
                """,
                Long.class,
                tenantId,
                name
        );
    }

    private int countGrants(long applicationId, long knowledgeBaseId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM application_grant
                WHERE application_id = ? AND knowledge_base_id = ?
                """,
                Integer.class,
                applicationId,
                knowledgeBaseId
        );
    }

    private Map<String, Object> storedGrant(long applicationId, long knowledgeBaseId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT id, permission, status, granted_at, granted_by, revoked_at, revoked_by
                FROM application_grant
                WHERE application_id = ? AND knowledge_base_id = ?
                """,
                applicationId,
                knowledgeBaseId
        );
    }

    private String grantEndpoint(long tenantId, long applicationId, long knowledgeBaseId) {
        return "/api/admin/v1/tenants/" + tenantId
                + "/applications/" + applicationId
                + "/grants/" + knowledgeBaseId;
    }

    private String applicationGrantsEndpoint(long tenantId, long applicationId) {
        return "/api/admin/v1/tenants/" + tenantId
                + "/applications/" + applicationId
                + "/grants";
    }

    private String knowledgeBaseGrantsEndpoint(long tenantId, long knowledgeBaseId) {
        return "/api/admin/v1/tenants/" + tenantId
                + "/knowledge-bases/" + knowledgeBaseId
                + "/grants";
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
