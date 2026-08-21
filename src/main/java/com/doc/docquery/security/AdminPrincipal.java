package com.doc.docquery.security;

import com.doc.docquery.enums.AdminRole;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 写入管理端 Session 的可信管理员身份快照。
 *
 * <p>包含管理员、所属租户和角色边界；每次请求仍由会话校验过滤器确认数据库
 * 中的账号、租户和角色没有失效。</p>
 */
public final class AdminPrincipal implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final Long tenantId;
    private final String loginName;
    private final String role;
    private final boolean enabled;
    private final LocalDateTime accountUpdatedAt;
    private String passwordHash;

    public AdminPrincipal(
            Long id,
            Long tenantId,
            String loginName,
            String passwordHash,
            String role,
            boolean enabled
    ) {
        this(id, tenantId, loginName, passwordHash, role, enabled, null);
    }

    public AdminPrincipal(
            Long id,
            Long tenantId,
            String loginName,
            String passwordHash,
            String role,
            boolean enabled,
            LocalDateTime accountUpdatedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.loginName = loginName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.accountUpdatedAt = accountUpdatedAt;
    }

    public Long id() {
        return id;
    }

    public Long tenantId() {
        return tenantId;
    }

    public String loginName() {
        return loginName;
    }

    public String role() {
        return role;
    }

    public LocalDateTime accountUpdatedAt() {
        return accountUpdatedAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(AdminRole.authorityOf(role)));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginName;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    /** 认证完成后擦除 Session 身份中的密码哈希副本。 */
    public void eraseCredentials() {
        passwordHash = null;
    }
}
