package com.doc.docquery;

import com.doc.docquery.enums.GrantPermission;
import com.doc.docquery.security.ApplicationCredentialAuthenticationException;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.security.KnowledgeBaseAccessContext;
import com.doc.docquery.security.KnowledgeBaseAccessDeniedException;
import com.doc.docquery.service.AdminBootstrapService;
import com.doc.docquery.service.ApplicationCredentialResolver;
import com.doc.docquery.service.KnowledgeBaseAccessAuthorizer;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * N1 身份与授权完整业务链路验收。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
class N1AcceptanceIT {

    private static final String CSRF_ENDPOINT = "/api/admin/v1/auth/csrf";
    private static final String LOGIN_ENDPOINT = "/api/admin/v1/auth/login";
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
    private AdminBootstrapService adminBootstrapService;

    @Autowired
    private ApplicationCredentialResolver credentialResolver;

    @Autowired
    private KnowledgeBaseAccessAuthorizer accessAuthorizer;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
    }

    @Test
    void completeN1WorkflowEnforcesTenantPermissionAndRevocationBoundaries()
            throws Exception {
        String platformLogin = adminBootstrapService.createInitialPlatformAdmin(
                "n1.platform",
                PASSWORD.toCharArray()
        );
        assertThat(platformLogin).isEqualTo("n1.platform");

        AuthSession platform = login(platformLogin);
        mockMvc.perform(get(ME_ENDPOINT).session(platform.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("1"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        long tenantAId = createTenant(
                platform,
                "N1 Acceptance Tenant A",
                "n1.tenant-a",
                PASSWORD
        );
        long tenantBId = createTenant(
                platform,
                "N1 Acceptance Tenant B",
                "n1.tenant-b",
                PASSWORD
        );

        AuthSession tenantA = login("n1.tenant-a");
        mockMvc.perform(get(ME_ENDPOINT).session(tenantA.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("2"))
                .andExpect(jsonPath("$.tenantId").value(tenantAId));

        long applicationAId = createApplication(tenantA, tenantAId);
        long knowledgeBaseAId = createKnowledgeBase(
                tenantA,
                tenantAId,
                "N1 Acceptance Knowledge A"
        );

        AuthSession tenantB = login("n1.tenant-b");
        long knowledgeBaseBId = createKnowledgeBase(
                tenantB,
                tenantBId,
                "N1 Acceptance Knowledge B"
        );

        CreatedCredential credential = createCredential(
                tenantA,
                tenantAId,
                applicationAId
        );
        ApplicationCredentialPrincipal principal = credentialResolver.resolve(
                credential.value()
        );
        assertThat(principal.getCredentialId()).isEqualTo(credential.id());
        assertThat(principal.getApplicationId()).isEqualTo(applicationAId);
        assertThat(principal.getTenantId()).isEqualTo(tenantAId);

        putGrant(tenantA, tenantAId, applicationAId, knowledgeBaseAId, "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("1"));
        KnowledgeBaseAccessContext readContext = accessAuthorizer.authorize(
                principal,
                knowledgeBaseAId,
                GrantPermission.READ
        );
        assertThat(readContext.getGrantedPermission()).isEqualTo("1");
        assertAuthorizationDenied(
                principal,
                knowledgeBaseAId,
                GrantPermission.WRITE,
                KnowledgeBaseAccessDeniedException.Reason.INSUFFICIENT_PERMISSION
        );

        putGrant(tenantA, tenantAId, applicationAId, knowledgeBaseAId, "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("3"));
        accessAuthorizer.authorize(principal, knowledgeBaseAId, GrantPermission.READ);
        accessAuthorizer.authorize(principal, knowledgeBaseAId, GrantPermission.WRITE);

        putGrant(tenantA, tenantAId, applicationAId, knowledgeBaseBId, "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_grant",
                Integer.class
        )).isEqualTo(1);

        revokeGrant(tenantA, tenantAId, applicationAId, knowledgeBaseAId);
        assertAuthorizationDenied(
                principal,
                knowledgeBaseAId,
                GrantPermission.READ,
                KnowledgeBaseAccessDeniedException.Reason.GRANT_REVOKED
        );

        putGrant(tenantA, tenantAId, applicationAId, knowledgeBaseAId, "3")
                .andExpect(status().isOk());
        accessAuthorizer.authorize(principal, knowledgeBaseAId, GrantPermission.WRITE);

        revokeCredential(
                tenantA,
                tenantAId,
                applicationAId,
                credential.id()
        );
        ApplicationCredentialAuthenticationException credentialException =
                catchThrowableOfType(
                        ApplicationCredentialAuthenticationException.class,
                        () -> credentialResolver.resolve(credential.value())
                );
        assertThat(credentialException).isNotNull();
        assertThat(credentialException.reason()).isEqualTo(
                ApplicationCredentialAuthenticationException.Reason.CREDENTIAL_REVOKED
        );
        assertAuthorizationDenied(
                principal,
                knowledgeBaseAId,
                GrantPermission.READ,
                KnowledgeBaseAccessDeniedException.Reason.CREDENTIAL_UNAVAILABLE
        );
    }

    private long createTenant(
            AuthSession platform,
            String tenantName,
            String adminLoginName,
            String adminPassword
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/v1/tenants")
                        .session(platform.session())
                        .header(platform.csrfHeader(), platform.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantName":"%s",
                                  "adminLoginName":"%s",
                                  "adminPassword":"%s"
                                }
                                """.formatted(
                                tenantName,
                                adminLoginName,
                                adminPassword
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.status").value("1"))
                .andExpect(jsonPath("$.initialAdmin.role").value("2"))
                .andReturn();
        return JsonPath.<Number>read(
                result.getResponse().getContentAsString(),
                "$.tenant.id"
        ).longValue();
    }

    private long createApplication(AuthSession admin, long tenantId) throws Exception {
        MvcResult result = mockMvc.perform(post(applicationsEndpoint(tenantId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"n1-acceptance-app",
                                  "name":"N1 Acceptance Application",
                                  "environment":"PRODUCTION",
                                  "description":"N1 end-to-end acceptance"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.status").value("1"))
                .andReturn();
        return JsonPath.<Number>read(
                result.getResponse().getContentAsString(),
                "$.id"
        ).longValue();
    }

    private long createKnowledgeBase(
            AuthSession admin,
            long tenantId,
            String name
    ) throws Exception {
        MvcResult result = mockMvc.perform(post(knowledgeBasesEndpoint(tenantId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"%s",
                                  "description":"N1 end-to-end acceptance"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.status").value("1"))
                .andReturn();
        return JsonPath.<Number>read(
                result.getResponse().getContentAsString(),
                "$.id"
        ).longValue();
    }

    private CreatedCredential createCredential(
            AuthSession admin,
            long tenantId,
            long applicationId
    ) throws Exception {
        MvcResult result = mockMvc.perform(post(credentialsEndpoint(tenantId, applicationId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"N1 Acceptance Credential\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("1"))
                .andExpect(jsonPath("$.credential").isString())
                .andExpect(jsonPath("$.secretDigest").doesNotExist())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new CreatedCredential(
                JsonPath.<Number>read(body, "$.id").longValue(),
                JsonPath.read(body, "$.credential")
        );
    }

    private org.springframework.test.web.servlet.ResultActions putGrant(
            AuthSession admin,
            long tenantId,
            long applicationId,
            long knowledgeBaseId,
            String permission
    ) throws Exception {
        return mockMvc.perform(put(grantEndpoint(
                        tenantId,
                        applicationId,
                        knowledgeBaseId
                ))
                .session(admin.session())
                .header(admin.csrfHeader(), admin.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permission\":\"" + permission + "\"}"));
    }

    private void revokeGrant(
            AuthSession admin,
            long tenantId,
            long applicationId,
            long knowledgeBaseId
    ) throws Exception {
        mockMvc.perform(delete(grantEndpoint(tenantId, applicationId, knowledgeBaseId))
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());
    }

    private void revokeCredential(
            AuthSession admin,
            long tenantId,
            long applicationId,
            long credentialId
    ) throws Exception {
        mockMvc.perform(delete(credentialsEndpoint(tenantId, applicationId)
                        + "/" + credentialId)
                        .session(admin.session())
                        .header(admin.csrfHeader(), admin.csrfToken()))
                .andExpect(status().isNoContent());
    }

    private void assertAuthorizationDenied(
            ApplicationCredentialPrincipal principal,
            long knowledgeBaseId,
            GrantPermission requiredPermission,
            KnowledgeBaseAccessDeniedException.Reason expectedReason
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
        assertThat(exception.reason()).isEqualTo(expectedReason);
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

    private String applicationsEndpoint(long tenantId) {
        return "/api/admin/v1/tenants/" + tenantId + "/applications";
    }

    private String knowledgeBasesEndpoint(long tenantId) {
        return "/api/admin/v1/tenants/" + tenantId + "/knowledge-bases";
    }

    private String credentialsEndpoint(long tenantId, long applicationId) {
        return applicationsEndpoint(tenantId)
                + "/" + applicationId
                + "/credentials";
    }

    private String grantEndpoint(
            long tenantId,
            long applicationId,
            long knowledgeBaseId
    ) {
        return applicationsEndpoint(tenantId)
                + "/" + applicationId
                + "/grants/" + knowledgeBaseId;
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

    private record CreatedCredential(long id, String value) {
    }
}
