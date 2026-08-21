package com.doc.docquery.service.impl;

import com.doc.docquery.dto.CreateTenantAdministratorDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.ResetTenantAdministratorPasswordDTO;
import com.doc.docquery.dto.UpdateTenantAdministratorDTO;
import com.doc.docquery.entity.AdminUserEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.AdminUserMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.TenantAdministratorManagementService;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.TenantAdministratorVO;
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

/** 租户管理员账号管理实现；写操作由 Tenant 行锁串行化。 */
@Service
public class TenantAdministratorManagementServiceImpl
        implements TenantAdministratorManagementService {

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

    public TenantAdministratorManagementServiceImpl(
            TenantMapper tenantMapper,
            AdminUserMapper adminUserMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.tenantMapper = tenantMapper;
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<TenantAdministratorVO> listAdministrators(
            AdminPrincipal principal,
            long tenantId,
            PageQueryDTO query
    ) {
        requirePlatformAdmin(principal);
        requirePositiveId(tenantId, "Tenant ID");
        validatePage(query);
        requireTenant(tenantId);

        long offset = (long) query.getPage() * query.getSize();
        List<TenantAdministratorVO> items = adminUserMapper.findTenantAdministrators(
                        tenantId,
                        TENANT_ADMIN,
                        offset,
                        query.getSize()
                ).stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                query.getPage(),
                query.getSize(),
                adminUserMapper.countTenantAdministrators(tenantId, TENANT_ADMIN)
        );
    }

    @Override
    @Transactional
    public TenantAdministratorVO createAdministrator(
            AdminPrincipal principal,
            long tenantId,
            CreateTenantAdministratorDTO dto
    ) {
        requirePlatformAdmin(principal);
        requirePositiveId(tenantId, "Tenant ID");
        lockTenant(tenantId);
        String loginName = normalizeLoginName(dto == null ? null : dto.getLoginName());
        String password = dto == null ? null : dto.getPassword();
        validatePassword(password);
        if (adminUserMapper.countByLoginName(loginName) != 0) {
            throw conflict("ADMIN_LOGIN_CONFLICT", "Administrator login name already exists");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AdminUserEntity administrator = new AdminUserEntity(
                null,
                tenantId,
                loginName,
                passwordEncoder.encode(password),
                TENANT_ADMIN,
                ACTIVE,
                now,
                now
        );
        try {
            if (adminUserMapper.insert(administrator) != 1 || administrator.getId() == null) {
                throw new IllegalStateException("Tenant administrator was not created");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("ADMIN_LOGIN_CONFLICT", "Administrator login name already exists");
        }
        return toVO(loadAdministrator(tenantId, administrator.getId()));
    }

    @Override
    @Transactional
    public TenantAdministratorVO updateAdministrator(
            AdminPrincipal principal,
            long tenantId,
            long administratorId,
            UpdateTenantAdministratorDTO dto
    ) {
        requirePlatformAdmin(principal);
        requirePositiveId(tenantId, "Tenant ID");
        requirePositiveId(administratorId, "Administrator ID");
        lockTenant(tenantId);
        AdminUserEntity current = loadAdministrator(tenantId, administratorId);
        String status = validateStatus(dto == null ? null : dto.getStatus());
        if (status.equals(current.getStatus())) {
            return toVO(current);
        }
        if (DISABLED.equals(status)
                && adminUserMapper.countTenantAdministratorsByStatus(
                        tenantId,
                        TENANT_ADMIN,
                        ACTIVE
                ) <= 1) {
            throw conflict(
                    "LAST_ACTIVE_TENANT_ADMIN",
                    "The last active tenant administrator cannot be disabled"
            );
        }

        if (adminUserMapper.updateTenantAdministratorStatus(
                tenantId,
                administratorId,
                TENANT_ADMIN,
                status,
                LocalDateTime.now(ZoneOffset.UTC)
        ) != 1) {
            throw administratorNotFound();
        }
        return toVO(loadAdministrator(tenantId, administratorId));
    }

    @Override
    @Transactional
    public void resetPassword(
            AdminPrincipal principal,
            long tenantId,
            long administratorId,
            ResetTenantAdministratorPasswordDTO dto
    ) {
        requirePlatformAdmin(principal);
        requirePositiveId(tenantId, "Tenant ID");
        requirePositiveId(administratorId, "Administrator ID");
        String password = dto == null ? null : dto.getPassword();
        validatePassword(password);
        lockTenant(tenantId);
        loadAdministrator(tenantId, administratorId);
        if (adminUserMapper.updateTenantAdministratorPassword(
                tenantId,
                administratorId,
                TENANT_ADMIN,
                passwordEncoder.encode(password),
                LocalDateTime.now(ZoneOffset.UTC)
        ) != 1) {
            throw administratorNotFound();
        }
    }

    private TenantAdministratorVO toVO(AdminUserEntity administrator) {
        return new TenantAdministratorVO(
                administrator.getId(),
                administrator.getTenantId(),
                administrator.getLoginName(),
                administrator.getRole(),
                administrator.getStatus(),
                administrator.getCreatedAt().atOffset(ZoneOffset.UTC),
                administrator.getUpdatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private AdminUserEntity loadAdministrator(long tenantId, long administratorId) {
        AdminUserEntity administrator = adminUserMapper.findTenantAdministrator(
                tenantId,
                administratorId,
                TENANT_ADMIN
        );
        if (administrator == null) {
            throw administratorNotFound();
        }
        return administrator;
    }

    private void requireTenant(long tenantId) {
        if (tenantMapper.findById(tenantId) == null) {
            throw tenantNotFound();
        }
    }

    private TenantEntity lockTenant(long tenantId) {
        TenantEntity tenant = tenantMapper.findByIdForUpdate(tenantId);
        if (tenant == null) {
            throw tenantNotFound();
        }
        return tenant;
    }

    private void requirePlatformAdmin(AdminPrincipal principal) {
        if (principal == null || !PLATFORM_ADMIN.equals(principal.role())) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_ADMIN_MANAGEMENT_FORBIDDEN",
                    "Platform administrator is required"
            );
        }
    }

    private void requirePositiveId(long id, String label) {
        if (id < 1) {
            throw validation(label + " must be positive");
        }
    }

    private void validatePage(PageQueryDTO query) {
        if (query == null || query.getPage() < 0) {
            throw validation("Page must not be negative");
        }
        if (query.getSize() < 1 || query.getSize() > 100) {
            throw validation("Size must be between 1 and 100");
        }
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

    private String validateStatus(String status) {
        if (!ACTIVE.equals(status) && !DISABLED.equals(status)) {
            throw validation("Administrator status must be 1 (ACTIVE) or 2 (DISABLED)");
        }
        return status;
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException tenantNotFound() {
        return new BusinessException(NOT_FOUND, "TENANT_NOT_FOUND", "Tenant was not found");
    }

    private BusinessException administratorNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "TENANT_ADMIN_NOT_FOUND",
                "Tenant administrator was not found"
        );
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(CONFLICT, code, message);
    }
}
