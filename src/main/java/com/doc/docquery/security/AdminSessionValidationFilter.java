package com.doc.docquery.security;

import com.doc.docquery.service.AdminIdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 对已认证管理会话执行逐请求失效校验。
 *
 * <p>管理员被停用、角色变化或所属租户停用后，旧 Session 会立即失效，避免
 * 只依赖登录时生成的身份快照。</p>
 */
@Component
public class AdminSessionValidationFilter extends OncePerRequestFilter {

    private final AdminIdentityService adminIdentityService;
    private final LogoutHandler adminLogoutHandler;

    public AdminSessionValidationFilter(
            AdminIdentityService adminIdentityService,
            LogoutHandler adminLogoutHandler
    ) {
        this.adminIdentityService = adminIdentityService;
        this.adminLogoutHandler = adminLogoutHandler;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AdminPrincipal principal
                && !adminIdentityService.isSessionStillValid(principal)) {
            adminLogoutHandler.logout(request, response, authentication);
            writeUnauthenticated(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthenticated(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication is required\"}"
        );
    }
}
