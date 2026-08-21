package com.doc.docquery;

import com.doc.docquery.security.AdminPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthenticationIT {

    private static final String CSRF_ENDPOINT = "/api/admin/v1/auth/csrf";
    private static final String LOGIN_ENDPOINT = "/api/admin/v1/auth/login";
    private static final String LOGOUT_ENDPOINT = "/api/admin/v1/auth/logout";
    private static final String ME_ENDPOINT = "/api/admin/v1/auth/me";
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

    @Value("${server.servlet.session.timeout}")
    private String sessionTimeout;

    @Value("${server.servlet.session.cookie.http-only}")
    private boolean sessionCookieHttpOnly;

    @Value("${server.servlet.session.cookie.same-site}")
    private String sessionCookieSameSite;

    @Value("${server.servlet.session.cookie.secure}")
    private boolean sessionCookieSecure;

    @Test
    void loginRequiresCsrfAndRejectsBadOrDisabledCredentials() throws Exception {
        insertPlatformAdmin("security-login", "1");

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("security-login", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        CsrfSession wrongPassword = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(wrongPassword.session())
                        .header(wrongPassword.headerName(), wrongPassword.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("security-login", "Wrong Password 2026!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

        insertPlatformAdmin("security-disabled", "2");
        CsrfSession disabled = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(disabled.session())
                        .header(disabled.headerName(), disabled.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("security-disabled", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

        CsrfSession oversized = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(oversized.session())
                        .header(oversized.headerName(), oversized.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("security-login", "x".repeat(73))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void loginCreatesEightHourSessionSupportsMeAndLogout() throws Exception {
        long adminId = insertPlatformAdmin("login-session", "1");
        CsrfSession csrf = fetchCsrf(null);
        String anonymousSessionId = csrf.session().getId();

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("  LOGIN-SESSION  ", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(adminId))
                .andExpect(jsonPath("$.loginName").value("login-session"))
                .andExpect(jsonPath("$.role").value("1"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        assertThat(csrf.session().getId()).isNotEqualTo(anonymousSessionId);
        assertThat(sessionTimeout).isEqualTo("8h");
        assertThat(sessionCookieHttpOnly).isTrue();
        assertThat(sessionCookieSameSite).isEqualTo("lax");
        assertThat(sessionCookieSecure).isFalse();

        SecurityContext savedContext = (SecurityContext) csrf.session().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );
        assertThat(savedContext).isNotNull();
        assertThat(savedContext.getAuthentication().getPrincipal()).isInstanceOf(AdminPrincipal.class);
        AdminPrincipal principal = (AdminPrincipal) savedContext.getAuthentication().getPrincipal();
        assertThat(principal.getPassword()).isNull();

        mockMvc.perform(get(ME_ENDPOINT).session(csrf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(adminId))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        mockMvc.perform(post(LOGOUT_ENDPOINT).session(csrf.session()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(ME_ENDPOINT).session(csrf.session()))
                .andExpect(status().isOk());

        CsrfSession refreshed = fetchCsrf(csrf.session());
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .session(refreshed.session())
                        .header(refreshed.headerName(), refreshed.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ME_ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void disablingAdministratorInvalidatesExistingSession() throws Exception {
        long adminId = insertPlatformAdmin("disable-session", "1");
        MockHttpSession session = login("disable-session", PASSWORD);

        jdbcTemplate.update(
                "UPDATE admin_user SET status = '2' WHERE id = ?",
                adminId
        );

        mockMvc.perform(get(ME_ENDPOINT).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void disablingTenantInvalidatesTenantAdministratorSession() throws Exception {
        long tenantId = insertTenant("Session Tenant");
        insertTenantAdmin(tenantId, "tenant-session");
        MockHttpSession session = login("tenant-session", PASSWORD);

        mockMvc.perform(get(ME_ENDPOINT).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("2"))
                .andExpect(jsonPath("$.tenantId").value(tenantId));

        jdbcTemplate.update(
                "UPDATE tenant SET status = '2' WHERE id = ?",
                tenantId
        );

        mockMvc.perform(get(ME_ENDPOINT).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private MockHttpSession login(String loginName, String password) throws Exception {
        CsrfSession csrf = fetchCsrf(null);
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(loginName, password)))
                .andExpect(status().isOk());
        return csrf.session();
    }

    private CsrfSession fetchCsrf(MockHttpSession existingSession) throws Exception {
        MockHttpServletRequestBuilder request = get(CSRF_ENDPOINT);
        if (existingSession != null) {
            request.session(existingSession);
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        String body = result.getResponse().getContentAsString();
        String headerName = JsonPath.read(body, "$.headerName");
        String token = JsonPath.read(body, "$.token");
        return new CsrfSession(session, headerName, token);
    }

    private long insertPlatformAdmin(String loginName, String status) {
        return insertAdmin(null, loginName, "1", status);
    }

    private void insertTenantAdmin(long tenantId, String loginName) {
        insertAdmin(tenantId, loginName, "2", "1");
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
                "SELECT id FROM tenant WHERE name = ?",
                Long.class,
                name
        );
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
}
