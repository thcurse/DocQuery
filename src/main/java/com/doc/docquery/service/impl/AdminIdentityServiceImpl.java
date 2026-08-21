package com.doc.docquery.service.impl;

import com.doc.docquery.dto.AdminIdentityDTO;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.mapper.AdminUserMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.AdminIdentityService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 管理员身份加载与会话复核实现。
 *
 * <p>平台管理员只要求自身有效；租户管理员还要求所属租户处于 ACTIVE。</p>
 */
@Service
public class AdminIdentityServiceImpl implements AdminIdentityService {

    private static final Pattern LOGIN_NAME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{2,63}"
    );
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String PLATFORM_ADMIN = AdminRole.PLATFORM_ADMIN.getCode();
    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();

    private final AdminUserMapper adminUserMapper;

    public AdminIdentityServiceImpl(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String requestedLoginName) {
        String loginName = normalizeLoginName(requestedLoginName);
        AdminIdentityDTO dto = adminUserMapper.findForLogin(loginName);
        if (dto == null) {
            throw new UsernameNotFoundException("Administrator was not found");
        }
        return new AdminPrincipal(
                dto.getId(),
                dto.getTenantId(),
                dto.getLoginName(),
                dto.getPasswordHash(),
                dto.getRole(),
                isActiveScope(
                        dto.getTenantId(),
                        dto.getRole(),
                        dto.getAdminStatus(),
                        dto.getTenantStatus()
                ),
                dto.getAccountUpdatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionStillValid(AdminPrincipal principal) {
        AdminIdentityDTO dto = adminUserMapper.findForSession(principal.id());
        return dto != null
                && Objects.equals(dto.getTenantId(), principal.tenantId())
                && Objects.equals(dto.getLoginName(), principal.loginName())
                && Objects.equals(dto.getRole(), principal.role())
                && (principal.accountUpdatedAt() == null
                || Objects.equals(dto.getAccountUpdatedAt(), principal.accountUpdatedAt()))
                && isActiveScope(
                        dto.getTenantId(),
                        dto.getRole(),
                        dto.getAdminStatus(),
                        dto.getTenantStatus()
                );
    }

    private String normalizeLoginName(String requestedLoginName) {
        if (requestedLoginName == null) {
            throw new UsernameNotFoundException("Administrator was not found");
        }
        String loginName = requestedLoginName.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN_NAME_PATTERN.matcher(loginName).matches()) {
            throw new UsernameNotFoundException("Administrator was not found");
        }
        return loginName;
    }

    private boolean isActiveScope(
            Long tenantId,
            String role,
            String adminStatus,
            String tenantStatus
    ) {
        if (!ACTIVE.equals(adminStatus)) {
            return false;
        }
        if (PLATFORM_ADMIN.equals(role)) {
            return tenantId == null;
        }
        if (TENANT_ADMIN.equals(role)) {
            return tenantId != null && ACTIVE.equals(tenantStatus);
        }
        return false;
    }
}
