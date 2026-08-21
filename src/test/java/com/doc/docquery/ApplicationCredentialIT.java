package com.doc.docquery;

import com.doc.docquery.dto.CreateCredentialDTO;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.security.ApplicationCredentialAuthenticationException;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.service.ApplicationCredentialResolver;
import com.doc.docquery.service.CredentialService;
import com.doc.docquery.vo.CreatedCredentialVO;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.APPLICATION_UNAVAILABLE;
import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.CREDENTIAL_REVOKED;
import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.INVALID_CREDENTIAL;
import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.TENANT_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@ExtendWith(OutputCaptureExtension.class)
class ApplicationCredentialIT {

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
    private ApplicationCredentialResolver credentialResolver;

    @Autowired
    private CredentialService credentialService;

    private long tenantAId;
    private long tenantBId;
    private long adminAId;
    private long applicationAId;
    private long applicationBId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");

        tenantAId = insertTenant("Credential Tenant A");
        tenantBId = insertTenant("Credential Tenant B");
        adminAId = insertAdmin(tenantAId, "credential-a.admin", "2");
        insertAdmin(tenantBId, "credential-b.admin", "2");
        insertAdmin(null, "credential.platform", "1");
        applicationAId = insertApplication(tenantAId, "credential-app-a", "Credential App A");
        applicationBId = insertApplication(tenantBId, "credential-app-b", "Credential App B");
    }

    @Test
    void credentialIsReturnedOnceStoredAsDigestAndResolvedWithoutUsageWrite()
            throws Exception {
        AuthSession admin = login("credential-a.admin");
        MvcResult createdResult = mockMvc.perform(post(credentialsEndpoint(
                                tenantAId,
                                applicationAId
                        ))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Production rotation  \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.name").value("Production rotation"))
                .andExpect(jsonPath("$.keyIdPrefix").isString())
                .andExpect(jsonPath("$.credential").isString())
                .andExpect(jsonPath("$.secretDigest").doesNotExist())
                .andExpect(jsonPath("$.createdBy").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andReturn();

        String createdBody = createdResult.getResponse().getContentAsString();
        long credentialId = JsonPath.<Number>read(createdBody, "$.id").longValue();
        String fullCredential = JsonPath.read(createdBody, "$.credential");
        assertThat(createdResult.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/credentials/" + credentialId);
        assertThat(fullCredential.matches(
                "dq_app_[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}"
        )).isTrue();
        int separator = fullCredential.indexOf('.');
        String keyId = fullCredential.substring("dq_app_".length(), separator);
        String secret = fullCredential.substring(separator + 1);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT key_id, secret_digest, last_used_at FROM credential WHERE id = ?",
                credentialId
        );
        assertThat(stored.get("key_id")).isEqualTo(keyId);
        assertThat(MessageDigest.isEqual(
                (byte[]) stored.get("secret_digest"),
                MessageDigest.getInstance("SHA-256")
                        .digest(secret.getBytes(StandardCharsets.UTF_8))
        )).isTrue();
        assertThat(stored.get("last_used_at")).isNull();
        assertThat(stored.values().stream().noneMatch(
                value -> fullCredential.equals(value) || secret.equals(value)
        )).isTrue();

        MvcResult listResult = mockMvc.perform(get(credentialsEndpoint(
                                tenantAId,
                                applicationAId
                        ))
                        .session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(credentialId))
                .andExpect(jsonPath("$.items[0].keyIdPrefix").value(keyId.substring(0, 8)))
                .andExpect(jsonPath("$.items[0].credential").doesNotExist())
                .andExpect(jsonPath("$.items[0].keyId").doesNotExist())
                .andExpect(jsonPath("$.items[0].secretDigest").doesNotExist())
                .andReturn();
        String listBody = listResult.getResponse().getContentAsString();
        assertThat(!listBody.contains(fullCredential)
                && !listBody.contains(secret)
                && !listBody.contains(keyId)).isTrue();

        ApplicationCredentialPrincipal resolved = credentialResolver.resolve(fullCredential);
        assertThat(resolved.getCredentialId()).isEqualTo(credentialId);
        assertThat(resolved.getApplicationId()).isEqualTo(applicationAId);
        assertThat(resolved.getTenantId()).isEqualTo(tenantAId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM credential WHERE id = ?",
                LocalDateTime.class,
                credentialId
        )).isNull();
    }

    @Test
    void twoCredentialRotationLimitAndIdempotentRevocationAreEnforced()
            throws Exception {
        AuthSession admin = login("credential-a.admin");
        Created first = createCredential(admin, tenantAId, applicationAId, "Old credential");
        Created second = createCredential(admin, tenantAId, applicationAId, "New credential");

        mockMvc.perform(post(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Third credential\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_CREDENTIAL_LIMIT_REACHED"));

        mockMvc.perform(delete(credentialsEndpoint(tenantAId, applicationAId)
                                + "/" + first.id())
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());
        Map<String, Object> firstRevocation = jdbcTemplate.queryForMap(
                "SELECT status, revoked_at, revoked_by FROM credential WHERE id = ?",
                first.id()
        );
        mockMvc.perform(delete(credentialsEndpoint(tenantAId, applicationAId)
                                + "/" + first.id())
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());
        Map<String, Object> repeatedRevocation = jdbcTemplate.queryForMap(
                "SELECT status, revoked_at, revoked_by FROM credential WHERE id = ?",
                first.id()
        );
        assertThat(repeatedRevocation).isEqualTo(firstRevocation);
        assertThat(firstRevocation.get("status")).isEqualTo("3");

        ApplicationCredentialAuthenticationException revoked = catchThrowableOfType(
                ApplicationCredentialAuthenticationException.class,
                () -> credentialResolver.resolve(first.credential())
        );
        assertThat(revoked.reason()).isEqualTo(CREDENTIAL_REVOKED);
        assertThat(credentialResolver.resolve(second.credential()).getCredentialId())
                .isEqualTo(second.id());

        createCredential(admin, tenantAId, applicationAId, "Replacement credential");
        assertThat(countActiveCredentials(applicationAId)).isEqualTo(2);
    }

    @Test
    void invalidCredentialsFailWithoutEchoingSecrets(CapturedOutput output) throws Exception {
        AuthSession admin = login("credential-a.admin");
        Created created = createCredential(
                admin,
                tenantAId,
                applicationAId,
                "Secret logging check"
        );
        String wrongSecret = created.credential().substring(
                0,
                created.credential().length() - 1
        ) + (created.credential().endsWith("A") ? "B" : "A");
        String unknown = "dq_app_" + "A".repeat(22) + "." + "B".repeat(43);

        for (String rejected : List.of("malformed", unknown, wrongSecret)) {
            ApplicationCredentialAuthenticationException exception = catchThrowableOfType(
                    ApplicationCredentialAuthenticationException.class,
                    () -> credentialResolver.resolve(rejected)
            );
            assertThat(exception.reason()).isEqualTo(INVALID_CREDENTIAL);
            assertThat(exception.getMessage().contains(rejected)).isFalse();
        }
        String secret = created.credential().substring(created.credential().indexOf('.') + 1);
        assertThat(!output.getAll().contains(created.credential())
                && !output.getAll().contains(secret)).isTrue();
    }

    @Test
    void administratorScopeRoleCsrfAndForeignResourcesAreEnforced() throws Exception {
        AuthSession adminA = login("credential-a.admin");
        AuthSession adminB = login("credential-b.admin");
        AuthSession platform = login("credential.platform");
        Created foreign = createCredential(
                adminB,
                tenantBId,
                applicationBId,
                "Foreign credential"
        );
        Created local = createCredential(
                adminA,
                tenantAId,
                applicationAId,
                "Local credential"
        );

        mockMvc.perform(get(credentialsEndpoint(tenantBId, applicationBId))
                        .session(adminA.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SCOPE_FORBIDDEN"));
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationBId))
                        .session(adminA.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        mockMvc.perform(delete(credentialsEndpoint(tenantAId, applicationAId)
                                + "/" + foreign.id())
                        .session(adminA.session())
                        .header(adminA.csrfHeader(), adminA.csrfToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .session(platform.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post(credentialsEndpoint(tenantAId, applicationAId))
                        .session(adminA.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missing CSRF\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + local.credential()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void disabledApplicationAllowsCleanupAndAllStatusChangesAreImmediate() throws Exception {
        AuthSession admin = login("credential-a.admin");
        Created original = createCredential(
                admin,
                tenantAId,
                applicationAId,
                "Credential before disable"
        );

        jdbcTemplate.update(
                "UPDATE application SET status = '2' WHERE id = ?",
                applicationAId
        );
        assertCredentialFailure(original.credential(), APPLICATION_UNAVAILABLE);
        mockMvc.perform(post(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked while disabled\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ACTIVE"));
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session()))
                .andExpect(status().isOk());
        mockMvc.perform(delete(credentialsEndpoint(tenantAId, applicationAId)
                                + "/" + original.id())
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());

        jdbcTemplate.update(
                "UPDATE application SET status = '1' WHERE id = ?",
                applicationAId
        );
        assertCredentialFailure(original.credential(), CREDENTIAL_REVOKED);
        Created current = createCredential(
                admin,
                tenantAId,
                applicationAId,
                "Credential before tenant disable"
        );
        jdbcTemplate.update("UPDATE tenant SET status = '2' WHERE id = ?", tenantAId);
        assertCredentialFailure(current.credential(), TENANT_UNAVAILABLE);
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void paginationValidationAndConcurrentCreationKeepTheTwoCredentialLimit()
            throws Exception {
        AuthSession admin = login("credential-a.admin");
        Created existing = createCredential(
                admin,
                tenantAId,
                applicationAId,
                "Existing credential"
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AdminPrincipal principal = new AdminPrincipal(
                adminAId,
                tenantAId,
                "credential-a.admin",
                null,
                "2",
                true
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> create = () -> {
                ready.countDown();
                start.await();
                CreateCredentialDTO dto = new CreateCredentialDTO();
                dto.setName("Concurrent credential");
                try {
                    CreatedCredentialVO result = credentialService.createCredential(
                            principal,
                            tenantAId,
                            applicationAId,
                            dto
                    );
                    return "CREATED:" + result.getId();
                } catch (BusinessException exception) {
                    return exception.code();
                }
            };
            Future<String> first = executor.submit(create);
            Future<String> second = executor.submit(create);
            ready.await();
            start.countDown();
            List<String> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(value -> value.startsWith("CREATED:"))
                    .hasSize(1);
            assertThat(outcomes).contains("ACTIVE_CREDENTIAL_LIMIT_REACHED");
        } finally {
            executor.shutdownNow();
        }

        assertThat(countActiveCredentials(applicationAId)).isEqualTo(2);
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(
                        org.hamcrest.Matchers.not(existing.id())
                ))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(credentialsEndpoint(tenantAId, applicationAId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Created createCredential(
            AuthSession admin,
            long tenantId,
            long applicationId,
            String name
    ) throws Exception {
        MvcResult result = mockMvc.perform(post(credentialsEndpoint(tenantId, applicationId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Created(
                JsonPath.<Number>read(body, "$.id").longValue(),
                JsonPath.read(body, "$.credential")
        );
    }

    private void assertCredentialFailure(
            String credential,
            ApplicationCredentialAuthenticationException.Reason reason
    ) {
        ApplicationCredentialAuthenticationException exception = catchThrowableOfType(
                ApplicationCredentialAuthenticationException.class,
                () -> credentialResolver.resolve(credential)
        );
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

    private long countActiveCredentials(long applicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credential WHERE application_id = ? AND status = '1'",
                Long.class,
                applicationId
        );
    }

    private String credentialsEndpoint(long tenantId, long applicationId) {
        return "/api/admin/v1/tenants/" + tenantId
                + "/applications/" + applicationId
                + "/credentials";
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

    private record Created(long id, String credential) {
    }
}
