package com.doc.docquery;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TenantObjectManagementIT {

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

    private long tenantAId;
    private long tenantBId;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");

        tenantAId = insertTenant("Tenant A");
        tenantBId = insertTenant("Tenant B");
        insertAdmin(tenantAId, "tenant-a.admin", "2");
        insertAdmin(tenantBId, "tenant-b.admin", "2");
        insertAdmin(null, "platform.objects", "1");
    }

    @Test
    void tenantAdministratorCreatesReadsAndUpdatesApplication() throws Exception {
        AuthSession admin = login("tenant-a.admin");
        String applications = applicationsEndpoint(tenantAId);

        MvcResult createResult = mockMvc.perform(post(applications)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(
                                "  WORK-ORDER-PRODUCTION  ",
                                "  工单系统  ",
                                "PRODUCTION",
                                "  生产工单业务后端  "
                        )))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(
                        "/api/admin/v1/tenants/[0-9]+/applications/[0-9]+"
                )))
                .andExpect(jsonPath("$.tenantId").value(tenantAId))
                .andExpect(jsonPath("$.code").value("work-order-production"))
                .andExpect(jsonPath("$.name").value("工单系统"))
                .andExpect(jsonPath("$.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.description").value("生产工单业务后端"))
                .andExpect(jsonPath("$.status").value("1"))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.updatedAt").value(endsWith("Z")))
                .andReturn();

        String body = createResult.getResponse().getContentAsString();
        long applicationId = JsonPath.<Number>read(body, "$.id").longValue();
        assertThat(body)
                .doesNotContain("credentialCount")
                .doesNotContain("grantCount")
                .doesNotContain("lastUsedAt");

        mockMvc.perform(get(applications + "/" + applicationId)
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("work-order-production"));

        mockMvc.perform(patch(applications + "/" + applicationId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"another-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APPLICATION_CODE_IMMUTABLE"));

        mockMvc.perform(patch(applications + "/" + applicationId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null,\"status\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("生产工单业务后端"));

        mockMvc.perform(patch(applications + "/" + applicationId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"工单系统新版",
                                  "environment":"TESTING",
                                  "description":"",
                                  "status":"2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("work-order-production"))
                .andExpect(jsonPath("$.name").value("工单系统新版"))
                .andExpect(jsonPath("$.environment").value("TESTING"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.status").value("2"));

        Map<String, Object> saved = jdbcTemplate.queryForMap(
                """
                SELECT code, name, environment, description, status
                FROM application
                WHERE id = ?
                """,
                applicationId
        );
        assertThat(saved.get("code")).isEqualTo("work-order-production");
        assertThat(saved.get("name")).isEqualTo("工单系统新版");
        assertThat(saved.get("environment")).isEqualTo("TESTING");
        assertThat(saved.get("description")).isNull();
        assertThat(saved.get("status")).isEqualTo("2");
    }

    @Test
    void applicationPaginationValidationAndNotFoundAreStable() throws Exception {
        long firstId = insertApplication(tenantAId, "first-app", "First App");
        long secondId = insertApplication(tenantAId, "second-app", "Second App");
        long thirdId = insertApplication(tenantAId, "third-app", "Third App");
        AuthSession admin = login("tenant-a.admin");
        String applications = applicationsEndpoint(tenantAId);

        mockMvc.perform(get(applications)
                        .session(admin.session())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(firstId))
                .andExpect(jsonPath("$.items[1].id").value(secondId))
                .andExpect(jsonPath("$.total").value(3));

        mockMvc.perform(get(applications)
                        .session(admin.session())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(thirdId));

        mockMvc.perform(get(applications)
                        .session(admin.session())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(applications)
                        .session(admin.session())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(applications + "/999999")
                        .session(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        mockMvc.perform(post(applications)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(
                                "Invalid_Code",
                                "Invalid App",
                                "PRODUCTION",
                                null
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(applications)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(
                                "long-description",
                                "Long Description",
                                "TESTING",
                                "x".repeat(1001)
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(applications)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(
                                "invalid-environment",
                                "Invalid Environment",
                                "STAGING",
                                null
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(patch(applications + "/" + firstId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(countApplications(tenantAId)).isEqualTo(3);
    }

    @Test
    void applicationCodeIsTenantScopedAndForeignResourceIsHidden() throws Exception {
        AuthSession adminA = login("tenant-a.admin");
        AuthSession adminB = login("tenant-b.admin");
        AuthSession platform = login("platform.objects");

        MvcResult aResult = createApplication(
                adminA,
                tenantAId,
                "shared-app",
                "Tenant A Shared"
        );
        MvcResult bResult = createApplication(
                adminB,
                tenantBId,
                "shared-app",
                "Tenant B Shared"
        );
        long bApplicationId = JsonPath.<Number>read(
                bResult.getResponse().getContentAsString(),
                "$.id"
        ).longValue();
        assertThat(aResult.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(applicationsEndpoint(tenantAId))
                        .session(adminA.session())
                        .header(adminA.csrfHeader(), adminA.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(
                                "shared-app",
                                "Duplicate",
                                "PRODUCTION",
                                null
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CODE_CONFLICT"));

        mockMvc.perform(get(applicationsEndpoint(tenantAId) + "/" + bApplicationId)
                        .session(adminA.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        mockMvc.perform(get(applicationsEndpoint(tenantBId) + "/" + bApplicationId)
                        .session(adminA.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));

        mockMvc.perform(get(applicationsEndpoint(tenantAId))
                        .session(platform.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void knowledgeBaseNameIsCaseSensitiveAndTenantScoped() throws Exception {
        AuthSession adminA = login("tenant-a.admin");
        AuthSession adminB = login("tenant-b.admin");

        createKnowledgeBase(adminA, tenantAId, "  FAQ  ", "Upper case");
        createKnowledgeBase(adminA, tenantAId, "faq", "Lower case");
        createKnowledgeBase(adminB, tenantBId, "FAQ", "Other tenant");

        mockMvc.perform(post(knowledgeBasesEndpoint(tenantAId))
                        .session(adminA.session())
                        .header(adminA.csrfHeader(), adminA.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knowledgeBaseJson("FAQ", "Duplicate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NAME_CONFLICT"));

        assertThat(countKnowledgeBases(tenantAId)).isEqualTo(2);
        assertThat(countKnowledgeBases(tenantBId)).isEqualTo(1);
    }

    @Test
    void knowledgeBasePaginationUpdateAndValidationAreStable() throws Exception {
        long firstId = insertKnowledgeBase(tenantAId, "First KB", "First");
        long secondId = insertKnowledgeBase(tenantAId, "Second KB", "Second");
        long thirdId = insertKnowledgeBase(tenantAId, "Third KB", "Third");
        AuthSession admin = login("tenant-a.admin");
        String knowledgeBases = knowledgeBasesEndpoint(tenantAId);

        mockMvc.perform(get(knowledgeBases)
                        .session(admin.session())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(firstId))
                .andExpect(jsonPath("$.items[1].id").value(secondId))
                .andExpect(jsonPath("$.total").value(3));

        mockMvc.perform(get(knowledgeBases)
                        .session(admin.session())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(thirdId));

        mockMvc.perform(patch(knowledgeBases + "/" + firstId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Renamed KB",
                                  "description":"",
                                  "status":"2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed KB"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.status").value("2"))
                .andExpect(jsonPath("$.updatedAt").value(endsWith("Z")));

        mockMvc.perform(patch(knowledgeBases + "/" + firstId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second KB\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NAME_CONFLICT"));

        mockMvc.perform(get(knowledgeBases + "/999999")
                        .session(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));

        mockMvc.perform(post(knowledgeBases)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knowledgeBaseJson("   ", "Invalid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(patch(knowledgeBases + "/" + secondId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tenantScopePlatformRoleCsrfAndDisabledTenantAreEnforced() throws Exception {
        long foreignApplicationId = insertApplication(
                tenantBId,
                "foreign-app",
                "Foreign App"
        );
        long foreignKnowledgeBaseId = insertKnowledgeBase(
                tenantBId,
                "Foreign KB",
                null
        );
        AuthSession adminA = login("tenant-a.admin");
        AuthSession platform = login("platform.objects");

        mockMvc.perform(get(applicationsEndpoint(tenantBId))
                        .session(adminA.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));

        mockMvc.perform(get(applicationsEndpoint(tenantAId) + "/" + foreignApplicationId)
                        .session(adminA.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        mockMvc.perform(get(knowledgeBasesEndpoint(tenantAId) + "/" + foreignKnowledgeBaseId)
                        .session(adminA.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));

        mockMvc.perform(get(knowledgeBasesEndpoint(tenantAId))
                        .session(platform.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post(applicationsEndpoint(tenantAId))
                        .session(adminA.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(
                                "missing-csrf",
                                "Missing CSRF",
                                "TESTING",
                                null
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        jdbcTemplate.update(
                "UPDATE tenant SET status = '2' WHERE id = ?",
                tenantAId
        );
        mockMvc.perform(get(applicationsEndpoint(tenantAId))
                        .session(adminA.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private MvcResult createApplication(
            AuthSession admin,
            long tenantId,
            String code,
            String name
    ) throws Exception {
        return mockMvc.perform(post(applicationsEndpoint(tenantId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(code, name, "PRODUCTION", null)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult createKnowledgeBase(
            AuthSession admin,
            long tenantId,
            String name,
            String description
    ) throws Exception {
        return mockMvc.perform(post(knowledgeBasesEndpoint(tenantId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knowledgeBaseJson(name, description)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("1"))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andReturn();
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

    private void insertAdmin(Long tenantId, String loginName, String role) {
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

    private long insertKnowledgeBase(long tenantId, String name, String description) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, ?, ?, '1', ?, ?)
                """,
                tenantId,
                name,
                description,
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

    private int countApplications(long tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application WHERE tenant_id = ?",
                Integer.class,
                tenantId
        );
    }

    private int countKnowledgeBases(long tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE tenant_id = ?",
                Integer.class,
                tenantId
        );
    }

    private String applicationsEndpoint(long tenantId) {
        return "/api/admin/v1/tenants/" + tenantId + "/applications";
    }

    private String knowledgeBasesEndpoint(long tenantId) {
        return "/api/admin/v1/tenants/" + tenantId + "/knowledge-bases";
    }

    private String applicationJson(
            String code,
            String name,
            String environment,
            String description
    ) {
        String descriptionJson = description == null
                ? "null"
                : "\"" + description + "\"";
        return """
                {
                  "code":"%s",
                  "name":"%s",
                  "environment":"%s",
                  "description":%s
                }
                """.formatted(code, name, environment, descriptionJson);
    }

    private String knowledgeBaseJson(String name, String description) {
        String descriptionJson = description == null
                ? "null"
                : "\"" + description + "\"";
        return """
                {"name":"%s","description":%s}
                """.formatted(name, descriptionJson);
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
