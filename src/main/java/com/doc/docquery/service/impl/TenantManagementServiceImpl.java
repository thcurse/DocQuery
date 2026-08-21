package com.doc.docquery.service.impl;

import com.doc.docquery.dto.CreateTenantDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateTenantDTO;
import com.doc.docquery.entity.AdminUserEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.AdminUserMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.TenantManagementService;
import com.doc.docquery.vo.CreatedTenantVO;
import com.doc.docquery.vo.InitialAdminVO;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.TenantVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/**
 * 租户与初始租户管理员管理实现。
 *
 * <p>创建租户时在同一事务写入租户和管理员，避免产生没有管理入口的孤立
 * 租户；只有平台管理员可以执行写操作。</p>
 */
@Service
public class TenantManagementServiceImpl implements TenantManagementService {

    private static final Pattern LOGIN_NAME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{2,63}"
    );
    private static final String PLATFORM_ADMIN = AdminRole.PLATFORM_ADMIN.getCode();
    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String DISABLED = StatusCode.DISABLED.getCode();
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private final TenantMapper tenantMapper;
    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;

    public TenantManagementServiceImpl(
            TenantMapper tenantMapper,
            AdminUserMapper adminUserMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.tenantMapper = tenantMapper;
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public CreatedTenantVO createTenant(AdminPrincipal principal, CreateTenantDTO dto) {
        requirePlatformAdmin(principal);
        String tenantName = normalizeTenantName(dto.getTenantName());
        String adminLoginName = normalizeLoginName(dto.getAdminLoginName());
        validatePassword(dto.getAdminPassword());

        if (adminUserMapper.countByLoginName(adminLoginName) != 0) {
            throw conflict("ADMIN_LOGIN_CONFLICT", "Administrator login name already exists");
        }

        // 租户与首个管理员在同一事务创建，任一步失败都会整体回滚。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TenantEntity tenant = new TenantEntity(null, tenantName, ACTIVE, now, now);
        if (tenantMapper.insert(tenant) != 1 || tenant.getId() == null) {
            throw new IllegalStateException("Tenant was not created");
        }

        AdminUserEntity adminUser = new AdminUserEntity(
                null,
                tenant.getId(),
                adminLoginName,
                passwordEncoder.encode(dto.getAdminPassword()),
                TENANT_ADMIN,
                ACTIVE,
                now,
                now
        );
        try {
            if (adminUserMapper.insert(adminUser) != 1 || adminUser.getId() == null) {
                throw new IllegalStateException("Initial tenant administrator was not created");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("ADMIN_LOGIN_CONFLICT", "Administrator login name already exists");
        }

        TenantEntity savedTenant = tenantMapper.findById(tenant.getId());
        if (savedTenant == null) {
            throw new IllegalStateException("Created tenant could not be loaded");
        }
        return new CreatedTenantVO(
                toTenantVO(savedTenant),
                new InitialAdminVO(
                        adminUser.getId(),
                        adminUser.getLoginName(),
                        adminUser.getRole(),
                        adminUser.getStatus(),
                        adminUser.getTenantId()
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<TenantVO> listTenants(AdminPrincipal principal, PageQueryDTO query) {
        requirePlatformAdmin(principal);
        validatePage(query);
        long offset = (long) query.getPage() * query.getSize();
        List<TenantVO> items = tenantMapper.findPage(offset, query.getSize()).stream()
                .map(this::toTenantVO)
                .toList();
        return new PageVO<>(items, query.getPage(), query.getSize(), tenantMapper.countAll());
    }

    @Override
    @Transactional(readOnly = true)
    public TenantVO getTenant(AdminPrincipal principal, long tenantId) {
        requirePositiveTenantId(tenantId);
        requireTenantReadScope(principal, tenantId);
        return loadTenant(tenantId);
    }

    @Override
    @Transactional
    public TenantVO updateTenant(
            AdminPrincipal principal,
            long tenantId,
            UpdateTenantDTO dto
    ) {
        requirePlatformAdmin(principal);
        requirePositiveTenantId(tenantId);
        if (dto.getName() == null && dto.getStatus() == null) {
            throw validation("At least one of name or status is required");
        }

        TenantEntity current = tenantMapper.findById(tenantId);
        if (current == null) {
            throw notFound();
        }
        current.setName(dto.getName() == null
                ? current.getName()
                : normalizeTenantName(dto.getName()));
        current.setStatus(dto.getStatus() == null
                ? current.getStatus()
                : validateStatus(dto.getStatus()));
        current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        if (tenantMapper.update(current) != 1) {
            throw notFound();
        }
        return loadTenant(tenantId);
    }

    private TenantVO loadTenant(long tenantId) {
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null) {
            throw notFound();
        }
        return toTenantVO(tenant);
    }

    private TenantVO toTenantVO(TenantEntity tenant) {
        return new TenantVO(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getCreatedAt().atOffset(ZoneOffset.UTC),
                tenant.getUpdatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private void requirePlatformAdmin(AdminPrincipal principal) {
        if (principal == null || !PLATFORM_ADMIN.equals(principal.role())) {
            throw forbidden("TENANT_MANAGEMENT_FORBIDDEN", "Platform administrator is required");
        }
    }

    private void requireTenantReadScope(AdminPrincipal principal, long tenantId) {
        if (principal == null) {
            throw forbidden("TENANT_SCOPE_FORBIDDEN", "Tenant is outside administrator scope");
        }
        if (PLATFORM_ADMIN.equals(principal.role())) {
            return;
        }
        if (TENANT_ADMIN.equals(principal.role())
                && principal.tenantId() != null
                && principal.tenantId() == tenantId) {
            return;
        }
        throw forbidden("TENANT_SCOPE_FORBIDDEN", "Tenant is outside administrator scope");
    }

    private void requirePositiveTenantId(long tenantId) {
        if (tenantId < 1) {
            throw validation("Tenant ID must be positive");
        }
    }

    private void validatePage(PageQueryDTO query) {
        if (query.getPage() < 0) {
            throw validation("Page must not be negative");
        }
        if (query.getSize() < 1 || query.getSize() > 100) {
            throw validation("Size must be between 1 and 100");
        }
    }

    private String normalizeTenantName(String requestedName) {
        if (requestedName == null) {
            throw validation("Tenant name is required");
        }
        String name = requestedName.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 200) {
            throw validation("Tenant name must contain between 1 and 200 characters");
        }
        return name;
    }

    private String normalizeLoginName(String requestedLoginName) {
        if (requestedLoginName == null) {
            throw validation("Administrator login name is required");
        }
        String loginName = requestedLoginName.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN_NAME_PATTERN.matcher(loginName).matches()) {
            throw validation("Administrator login name is invalid");
        }
        return loginName;
    }

    private void validatePassword(String password) {
        if (password == null
                || password.length() < MIN_PASSWORD_LENGTH
                || StandardCharsets.UTF_8.encode(password).remaining() > MAX_PASSWORD_UTF8_BYTES) {
            throw validation(
                    "Administrator password must contain at least 12 characters and at most 72 UTF-8 bytes"
            );
        }
    }

    private String validateStatus(String requestedStatus) {
        if (!ACTIVE.equals(requestedStatus) && !DISABLED.equals(requestedStatus)) {
            throw validation("Tenant status must be 1 (ACTIVE) or 2 (DISABLED)");
        }
        return requestedStatus;
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException notFound() {
        return new BusinessException(NOT_FOUND, "TENANT_NOT_FOUND", "Tenant was not found");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(CONFLICT, code, message);
    }

    private BusinessException forbidden(String code, String message) {
        return new BusinessException(FORBIDDEN, code, message);
    }
}
