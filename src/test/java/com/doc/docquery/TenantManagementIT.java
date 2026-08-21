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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TenantManagementIT {

    private static final String CSRF_ENDPOINT = "/api/admin/v1/auth/csrf";
    private static final String LOGIN_ENDPOINT = "/api/admin/v1/auth/login";
    private static final String ME_ENDPOINT = "/api/admin/v1/auth/me";
    private static final String TENANTS_ENDPOINT = "/api/admin/v1/tenants";
    private static final String PASSWORD = "Correct Horse Battery 2026!";
    private static final String NEW_PASSWORD = "Another Correct Password 2026!";

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

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
        insertAdmin(null, "platform.tenant", "1", "1");
    }

    @Test
    void platformCreatesTenantAndInitialAdministrator() throws Exception {
        AuthSession platform = login("platform.tenant", PASSWORD);

        MvcResult result = mockMvc.perform(post(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson(
                                "  Acme Support  ",
                                "  ACME.ADMIN  ",
                                PASSWORD
                        )))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/admin/v1/tenants/[0-9]+"
                )))
                .andExpect(jsonPath("$.tenant.name").value("Acme Support"))
                .andExpect(jsonPath("$.tenant.status").value("1"))
                .andExpect(jsonPath("$.tenant.createdAt").value(
                        org.hamcrest.Matchers.endsWith("Z")
                ))
                .andExpect(jsonPath("$.tenant.updatedAt").value(
                        org.hamcrest.Matchers.endsWith("Z")
                ))
                .andExpect(jsonPath("$.initialAdmin.loginName").value("acme.admin"))
                .andExpect(jsonPath("$.initialAdmin.role").value("2"))
                .andExpect(jsonPath("$.initialAdmin.status").value("1"))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Number tenantIdValue = JsonPath.read(response, "$.tenant.id");
        long tenantId = tenantIdValue.longValue();
        assertThat(JsonPath.<Number>read(response, "$.initialAdmin.tenantId").longValue())
                .isEqualTo(tenantId);
        assertThat(response).doesNotContain(PASSWORD);

        Map<String, Object> tenant = jdbcTemplate.queryForMap(
                "SELECT name, status FROM tenant WHERE id = ?",
                tenantId
        );
        assertThat(tenant.get("name")).isEqualTo("Acme Support");
        assertThat(tenant.get("status")).isEqualTo("1");

        Map<String, Object> admin = jdbcTemplate.queryForMap(
                """
                SELECT tenant_id, login_name, password_hash, role, status
                FROM admin_user
                WHERE login_name = 'acme.admin'
                """
        );
        assertThat(((Number) admin.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat(admin.get("role")).isEqualTo("2");
        assertThat(admin.get("status")).isEqualTo("1");
        String passwordHash = (String) admin.get("password_hash");
        assertThat(passwordHash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();

        AuthSession tenantAdmin = login("acme.admin", PASSWORD);
        mockMvc.perform(get(TENANTS_ENDPOINT + "/" + tenantId)
                        .session(tenantAdmin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId));
    }

    @Test
    void invalidOrConflictingCreationDoesNotCreateTenant() throws Exception {
        insertAdmin(null, "taken.admin", "1", "1");
        AuthSession platform = login("platform.tenant", PASSWORD);

        mockMvc.perform(post(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson("Conflict Tenant", "taken.admin", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_LOGIN_CONFLICT"));
        assertThat(count("tenant")).isZero();

        mockMvc.perform(post(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson("   ", "blank.admin", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("tenant")).isZero();

        mockMvc.perform(post(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson(
                                "Oversized Password Tenant",
                                "oversized.admin",
                                "x".repeat(73)
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("tenant")).isZero();
    }

    @Test
    void platformListsReadsAndUpdatesTenants() throws Exception {
        long firstId = insertTenant("First Tenant", "1");
        long secondId = insertTenant("Second Tenant", "1");
        long thirdId = insertTenant("Third Tenant", "1");
        AuthSession platform = login("platform.tenant", PASSWORD);

        mockMvc.perform(get(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(firstId))
                .andExpect(jsonPath("$.items[1].id").value(secondId))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3));

        mockMvc.perform(get(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(thirdId));

        mockMvc.perform(get(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(TENANTS_ENDPOINT)
                        .session(platform.session())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(TENANTS_ENDPOINT + "/" + secondId)
                        .session(platform.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Second Tenant"));

        mockMvc.perform(get(TENANTS_ENDPOINT + "/999999")
                        .session(platform.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));

        mockMvc.perform(patch(TENANTS_ENDPOINT + "/" + secondId)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  Renamed Tenant  ","status":"2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Tenant"))
                .andExpect(jsonPath("$.status").value("2"));

        Map<String, Object> updated = jdbcTemplate.queryForMap(
                "SELECT name, status FROM tenant WHERE id = ?",
                secondId
        );
        assertThat(updated.get("name")).isEqualTo("Renamed Tenant");
        assertThat(updated.get("status")).isEqualTo("2");

        mockMvc.perform(patch(TENANTS_ENDPOINT + "/" + firstId)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tenantAdministratorCanOnlyReadOwnTenant() throws Exception {
        long ownTenantId = insertTenant("Own Tenant", "1");
        long otherTenantId = insertTenant("Other Tenant", "1");
        insertAdmin(ownTenantId, "own.admin", "2", "1");
        insertAdmin(otherTenantId, "other.admin", "2", "1");
        AuthSession ownAdmin = login("own.admin", PASSWORD);

        mockMvc.perform(get(TENANTS_ENDPOINT + "/" + ownTenantId)
                        .session(ownAdmin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownTenantId));

        mockMvc.perform(get(TENANTS_ENDPOINT + "/" + otherTenantId)
                        .session(ownAdmin.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));

        mockMvc.perform(get(TENANTS_ENDPOINT)
                        .session(ownAdmin.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post(TENANTS_ENDPOINT)
                        .session(ownAdmin.session())
                        .header(ownAdmin.csrfHeader(), ownAdmin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson("Forbidden Tenant", "forbidden.admin", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(patch(TENANTS_ENDPOINT + "/" + ownTenantId)
                        .session(ownAdmin.session())
                        .header(ownAdmin.csrfHeader(), ownAdmin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"2\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void disablingTenantThroughApiInvalidatesTenantAdministratorSession() throws Exception {
        long tenantId = insertTenant("Disable Tenant", "1");
        insertAdmin(tenantId, "disable.tenant", "2", "1");
        AuthSession tenantAdmin = login("disable.tenant", PASSWORD);
        AuthSession platform = login("platform.tenant", PASSWORD);

        mockMvc.perform(patch(TENANTS_ENDPOINT + "/" + tenantId)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("2"));

        mockMvc.perform(get(ME_ENDPOINT).session(tenantAdmin.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        CsrfSession disabledLogin = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(disabledLogin.session())
                        .header(disabledLogin.headerName(), disabledLogin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("disable.tenant", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void platformListsAndCreatesTenantAdministratorsWithoutLeakingPassword() throws Exception {
        long tenantId = insertTenant("Administrator Tenant", "1");
        insertAdmin(tenantId, "first.admin", "2", "1");
        AuthSession platform = login("platform.tenant", PASSWORD);
        String endpoint = TENANTS_ENDPOINT + "/" + tenantId + "/administrators";

        mockMvc.perform(get(endpoint)
                        .session(platform.session())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].loginName").value("first.admin"))
                .andExpect(jsonPath("$.items[0].password").doesNotExist());

        MvcResult created = mockMvc.perform(post(endpoint)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginName":"  SECOND.ADMIN  ",
                                  "password":"%s"
                                }
                                """.formatted(NEW_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/admin/v1/tenants/" + tenantId + "/administrators/[0-9]+"
                )))
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.loginName").value("second.admin"))
                .andExpect(jsonPath("$.role").value("2"))
                .andExpect(jsonPath("$.status").value("1"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain(NEW_PASSWORD);

        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT password_hash, role, status FROM admin_user WHERE login_name = ?",
                "second.admin"
        );
        assertThat(saved.get("role")).isEqualTo("2");
        assertThat(saved.get("status")).isEqualTo("1");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, (String) saved.get("password_hash")))
                .isTrue();

        mockMvc.perform(post(endpoint)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginName":"second.admin","password":"%s"}
                                """.formatted(NEW_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_LOGIN_CONFLICT"));

        AuthSession tenantAdministrator = login("first.admin", PASSWORD);
        mockMvc.perform(get(endpoint).session(tenantAdministrator.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void lastActiveAdministratorIsProtectedAndStatusChangeInvalidatesSession() throws Exception {
        long tenantId = insertTenant("Status Tenant", "1");
        long firstId = insertAdmin(tenantId, "status.first", "2", "1");
        long secondId = insertAdmin(tenantId, "status.second", "2", "1");
        long otherTenantId = insertTenant("Other Status Tenant", "1");
        long otherAdminId = insertAdmin(otherTenantId, "status.other", "2", "1");
        AuthSession firstAdministrator = login("status.first", PASSWORD);
        AuthSession platform = login("platform.tenant", PASSWORD);
        String endpoint = TENANTS_ENDPOINT + "/" + tenantId + "/administrators/";

        mockMvc.perform(patch(endpoint + firstId)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("2"));

        mockMvc.perform(get(ME_ENDPOINT).session(firstAdministrator.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(patch(endpoint + secondId)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ACTIVE_TENANT_ADMIN"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM admin_user WHERE id = ?",
                String.class,
                secondId
        )).isEqualTo("1");

        mockMvc.perform(patch(endpoint + otherAdminId)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_ADMIN_NOT_FOUND"));
    }

    @Test
    void passwordResetInvalidatesSessionAndReplacesCredential() throws Exception {
        long tenantId = insertTenant("Password Tenant", "1");
        long administratorId = insertAdmin(tenantId, "password.admin", "2", "1");
        AuthSession administrator = login("password.admin", PASSWORD);
        AuthSession platform = login("platform.tenant", PASSWORD);
        String endpoint = TENANTS_ENDPOINT + "/" + tenantId
                + "/administrators/" + administratorId + "/password";

        mockMvc.perform(put(endpoint)
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"%s"}
                                """.formatted(NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ME_ENDPOINT).session(administrator.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        CsrfSession oldPasswordAttempt = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(oldPasswordAttempt.session())
                        .header(oldPasswordAttempt.headerName(), oldPasswordAttempt.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("password.admin", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

        AuthSession newPasswordSession = login("password.admin", NEW_PASSWORD);
        mockMvc.perform(get(ME_ENDPOINT).session(newPasswordSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value("password.admin"));
    }

    private AuthSession login(String loginName, String password) throws Exception {
        CsrfSession csrf = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(loginName, password)))
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

    private long insertTenant(String name, String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO tenant (name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """,
                name,
                status,
                now,
                now
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                name
        );
    }

    private long insertAdmin(Long tenantId, String loginName, String role, String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                loginName,
                passwordEncoder.encode(PASSWORD),
                role,
                status,
                now,
                now
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM admin_user WHERE login_name = ?",
                Long.class,
                loginName
        );
    }

    private int count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private String createTenantJson(
            String tenantName,
            String adminLoginName,
            String adminPassword
    ) {
        return """
                {
                  "tenantName":"%s",
                  "adminLoginName":"%s",
                  "adminPassword":"%s"
                }
                """.formatted(tenantName, adminLoginName, adminPassword);
    }

    private String loginJson(String loginName, String password) {
        return """
                {"loginName":"%s","password":"%s"}
                """.formatted(loginName, password);
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
