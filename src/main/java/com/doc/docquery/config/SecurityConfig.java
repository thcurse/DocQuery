package com.doc.docquery.config;

import com.doc.docquery.security.AdminSessionValidationFilter;
import com.doc.docquery.service.AdminIdentityService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 管理面会话认证、CSRF 与异常响应的安全配置。
 *
 * <p>管理 API 使用服务端 Session；应用凭证不经过该登录链路，而是在调用
 * 检索授权边界时由独立组件解析。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String ADMIN_API = "/api/admin/v1/**";
    private static final String CSRF_ENDPOINT = "/api/admin/v1/auth/csrf";
    private static final String LOGIN_ENDPOINT = "/api/admin/v1/auth/login";
    private static final String TENANTS_ENDPOINT = "/api/admin/v1/tenants";
    private static final String TENANT_DETAIL_ENDPOINT = "/api/admin/v1/tenants/*";
    private static final String TENANT_ADMINISTRATORS_ENDPOINT =
            "/api/admin/v1/tenants/*/administrators/**";
    private static final String APPLICATIONS_ENDPOINT =
            "/api/admin/v1/tenants/*/applications";
    private static final String APPLICATIONS_NESTED_ENDPOINT =
            "/api/admin/v1/tenants/*/applications/**";
    private static final String KNOWLEDGE_BASES_ENDPOINT =
            "/api/admin/v1/tenants/*/knowledge-bases";
    private static final String KNOWLEDGE_BASES_NESTED_ENDPOINT =
            "/api/admin/v1/tenants/*/knowledge-bases/**";
    private static final String QUERY_AUDITS_ENDPOINT =
            "/api/admin/v1/tenants/*/query-audits/**";
    private static final String PROCESSING_JOBS_ENDPOINT =
            "/api/admin/v1/tenants/*/processing-jobs/**";

    @Bean
    AuthenticationManager authenticationManager(
            AdminIdentityService adminIdentityService,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(adminIdentityService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(
            HttpSessionCsrfTokenRepository csrfTokenRepository
    ) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)
        ));
    }

    @Bean
    LogoutHandler adminLogoutHandler() {
        return new CompositeLogoutHandler(
                new SecurityContextLogoutHandler(),
                new CookieClearingLogoutHandler("JSESSIONID")
        );
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            HttpSessionCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            AdminSessionValidationFilter adminSessionValidationFilter
    ) throws Exception {
        http
                .securityMatcher(ADMIN_API)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, CSRF_ENDPOINT).permitAll()
                        .requestMatchers(HttpMethod.POST, LOGIN_ENDPOINT).permitAll()
                        .requestMatchers(HttpMethod.POST, TENANTS_ENDPOINT)
                        .hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.GET, TENANTS_ENDPOINT)
                        .hasRole("PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, TENANT_DETAIL_ENDPOINT)
                        .hasRole("PLATFORM_ADMIN")
                        .requestMatchers(TENANT_ADMINISTRATORS_ENDPOINT)
                        .hasRole("PLATFORM_ADMIN")
                        .requestMatchers(
                                APPLICATIONS_ENDPOINT,
                                APPLICATIONS_NESTED_ENDPOINT,
                                KNOWLEDGE_BASES_ENDPOINT,
                                KNOWLEDGE_BASES_NESTED_ENDPOINT,
                                QUERY_AUDITS_ENDPOINT,
                                PROCESSING_JOBS_ENDPOINT
                        )
                        .hasRole("TENANT_ADMIN")
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                )
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED",
                                        "Authentication is required"
                                )
                        )
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "ACCESS_DENIED",
                                        "Access is denied"
                                )
                        )
                )
                .addFilterBefore(adminSessionValidationFilter, AuthorizationFilter.class);

        return http.build();
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"
        );
    }
}
