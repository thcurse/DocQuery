package com.doc.docquery.controller;

import com.doc.docquery.dto.AdminLoginDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.AdminVO;
import com.doc.docquery.vo.CsrfTokenVO;
import com.doc.docquery.vo.ErrorVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 管理员会话认证接口。
 *
 * <p>负责 CSRF Token、登录、注销和当前身份查询；凭证校验委托给 Spring
 * Security，认证失败统一返回不泄露账号存在性的错误。</p>
 */
@RestController
@RequestMapping("/api/admin/v1/auth")
public class AdminAuthController {

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LogoutHandler adminLogoutHandler;

    public AdminAuthController(
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository,
            LogoutHandler adminLogoutHandler
    ) {
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.adminLogoutHandler = adminLogoutHandler;
    }

    /** 返回当前会话使用的 CSRF Token 元数据。 */
    @GetMapping("/csrf")
    public CsrfTokenVO csrf(CsrfToken token) {
        return new CsrfTokenVO(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    /** 校验管理员凭证并显式建立服务端 Session。 */
    @PostMapping("/login")
    public AdminVO login(
            @RequestBody AdminLoginDTO loginDTO,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String loginName = loginDTO.getLoginName() == null ? "" : loginDTO.getLoginName();
        String password = loginDTO.getPassword() == null ? "" : loginDTO.getPassword();
        // 在进入 BCrypt 前限制长度，避免无效输入触发昂贵哈希或被 72 字节截断。
        if (password.length() < 12 || StandardCharsets.UTF_8.encode(password).remaining() > 72) {
            throw new BadCredentialsException("Invalid login name or password");
        }

        Authentication authenticationRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(loginName, password);
        Authentication authentication = authenticationManager.authenticate(authenticationRequest);
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        // REST 登录不是默认表单登录，认证成功后需显式持久化 SecurityContext。
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return toAdminVO((AdminPrincipal) authentication.getPrincipal());
    }

    /** 注销当前会话并清理安全上下文。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        adminLogoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    /** 返回当前已认证管理员的非敏感身份信息。 */
    @GetMapping("/me")
    public AdminVO me(Authentication authentication) {
        return toAdminVO((AdminPrincipal) authentication.getPrincipal());
    }

    /** 将全部认证失败收敛为同一响应，避免泄露账号是否存在。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorVO> authenticationFailed() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ErrorVO("AUTHENTICATION_FAILED", "Invalid login name or password")
        );
    }

    private AdminVO toAdminVO(AdminPrincipal principal) {
        return new AdminVO(
                principal.id(),
                principal.loginName(),
                principal.role(),
                principal.tenantId()
        );
    }
}
