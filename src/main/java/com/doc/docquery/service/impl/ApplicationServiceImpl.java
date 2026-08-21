package com.doc.docquery.service.impl;

import com.doc.docquery.dto.CreateApplicationDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.dto.UpdateApplicationDTO;
import com.doc.docquery.entity.ApplicationEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.ApplicationMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.ApplicationService;
import com.doc.docquery.vo.ApplicationVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 租户应用管理实现。
 *
 * <p>Service 不信任路径 Tenant ID，每个入口都校验管理员角色、身份租户和
 * 数据库租户状态；应用稳定编码创建后不可修改。</p>
 */
@Service
public class ApplicationServiceImpl implements ApplicationService {

    private static final Pattern CODE_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9-]{2,63}"
    );
    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String DISABLED = StatusCode.DISABLED.getCode();
    private static final List<String> ENVIRONMENTS = List.of(
            "DEVELOPMENT",
            "TESTING",
            "PRODUCTION"
    );

    private final TenantMapper tenantMapper;
    private final ApplicationMapper applicationMapper;

    public ApplicationServiceImpl(
            TenantMapper tenantMapper,
            ApplicationMapper applicationMapper
    ) {
        this.tenantMapper = tenantMapper;
        this.applicationMapper = applicationMapper;
    }

    @Override
    @Transactional
    public ApplicationVO createApplication(
            AdminPrincipal principal,
            long tenantId,
            CreateApplicationDTO dto
    ) {
        requireActiveTenantScope(principal, tenantId);
        String code = normalizeCode(dto.getCode());
        String name = normalizeName(dto.getName());
        String environment = validateEnvironment(dto.getEnvironment());
        String description = normalizeDescription(dto.getDescription());

        if (applicationMapper.countByTenantAndCode(tenantId, code) != 0) {
            throw codeConflict();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ApplicationEntity application = new ApplicationEntity(
                null,
                tenantId,
                code,
                name,
                environment,
                description,
                ACTIVE,
                now,
                now
        );
        try {
            if (applicationMapper.insert(application) != 1 || application.getId() == null) {
                throw new IllegalStateException("Application was not created");
            }
        } catch (DuplicateKeyException exception) {
            throw codeConflict();
        }
        return loadApplication(tenantId, application.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<ApplicationVO> listApplications(
            AdminPrincipal principal,
            long tenantId,
            PageQueryDTO query
    ) {
        requireActiveTenantScope(principal, tenantId);
        validatePage(query);
        long offset = (long) query.getPage() * query.getSize();
        List<ApplicationVO> items = applicationMapper
                .findPageByTenant(tenantId, offset, query.getSize())
                .stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                query.getPage(),
                query.getSize(),
                applicationMapper.countByTenant(tenantId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationVO getApplication(
            AdminPrincipal principal,
            long tenantId,
            long applicationId
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId);
        return loadApplication(tenantId, applicationId);
    }

    @Override
    @Transactional
    public ApplicationVO updateApplication(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            UpdateApplicationDTO dto
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId);
        if (dto.getCode() != null) {
            throw new BusinessException(
                    VALIDATION,
                    "APPLICATION_CODE_IMMUTABLE",
                    "Application code cannot be changed"
            );
        }
        if (dto.getName() == null
                && dto.getEnvironment() == null
                && dto.getDescription() == null
                && dto.getStatus() == null) {
            throw validation("At least one mutable Application field is required");
        }

        ApplicationEntity application = findApplication(tenantId, applicationId);
        if (application == null) {
            throw notFound();
        }
        if (dto.getName() != null) {
            application.setName(normalizeName(dto.getName()));
        }
        if (dto.getEnvironment() != null) {
            application.setEnvironment(validateEnvironment(dto.getEnvironment()));
        }
        if (dto.getDescription() != null) {
            application.setDescription(normalizeDescription(dto.getDescription()));
        }
        if (dto.getStatus() != null) {
            application.setStatus(validateStatus(dto.getStatus()));
        }
        application.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        if (applicationMapper.update(application) != 1) {
            throw notFound();
        }
        return loadApplication(tenantId, applicationId);
    }

    private void requireActiveTenantScope(AdminPrincipal principal, long tenantId) {
        if (tenantId < 1) {
            throw validation("Tenant ID must be positive");
        }
        if (principal == null || !TENANT_ADMIN.equals(principal.role())) {
            throw forbidden(
                    "TENANT_RESOURCE_MANAGEMENT_FORBIDDEN",
                    "Tenant administrator is required"
            );
        }
        if (principal.tenantId() == null || principal.tenantId().longValue() != tenantId) {
            throw forbidden("TENANT_SCOPE_FORBIDDEN", "Tenant is outside administrator scope");
        }
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            throw forbidden("TENANT_NOT_ACTIVE", "Tenant is not active");
        }
    }

    private ApplicationVO loadApplication(long tenantId, long applicationId) {
        ApplicationEntity application = findApplication(tenantId, applicationId);
        if (application == null) {
            throw notFound();
        }
        return toVO(application);
    }

    private ApplicationEntity findApplication(long tenantId, long applicationId) {
        return applicationMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, applicationId)
        );
    }

    private ApplicationVO toVO(ApplicationEntity application) {
        return new ApplicationVO(
                application.getId(),
                application.getTenantId(),
                application.getCode(),
                application.getName(),
                application.getEnvironment(),
                application.getDescription(),
                application.getStatus(),
                application.getCreatedAt().atOffset(ZoneOffset.UTC),
                application.getUpdatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private String normalizeCode(String requestedCode) {
        if (requestedCode == null) {
            throw validation("Application code is required");
        }
        String code = requestedCode.strip().toLowerCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw validation("Application code is invalid");
        }
        return code;
    }

    private String normalizeName(String requestedName) {
        if (requestedName == null) {
            throw validation("Application name is required");
        }
        String name = requestedName.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 200) {
            throw validation("Application name must contain between 1 and 200 characters");
        }
        return name;
    }

    private String normalizeDescription(String requestedDescription) {
        if (requestedDescription == null) {
            return null;
        }
        String description = requestedDescription.strip();
        if (description.isEmpty()) {
            return null;
        }
        if (description.codePointCount(0, description.length()) > 1000) {
            throw validation("Description must not exceed 1000 characters");
        }
        return description;
    }

    private String validateEnvironment(String environment) {
        if (!ENVIRONMENTS.contains(environment)) {
            throw validation(
                    "Application environment must be DEVELOPMENT, TESTING or PRODUCTION"
            );
        }
        return environment;
    }

    private String validateStatus(String status) {
        if (!ACTIVE.equals(status) && !DISABLED.equals(status)) {
            throw validation("Status must be 1 (ACTIVE) or 2 (DISABLED)");
        }
        return status;
    }

    private void validatePage(PageQueryDTO query) {
        if (query.getPage() < 0) {
            throw validation("Page must not be negative");
        }
        if (query.getSize() < 1 || query.getSize() > 100) {
            throw validation("Size must be between 1 and 100");
        }
    }

    private void requirePositiveId(long applicationId) {
        if (applicationId < 1) {
            throw validation("Application ID must be positive");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException forbidden(String code, String message) {
        return new BusinessException(FORBIDDEN, code, message);
    }

    private BusinessException notFound() {
        return new BusinessException(
                NOT_FOUND,
                "APPLICATION_NOT_FOUND",
                "Application was not found"
        );
    }

    private BusinessException codeConflict() {
        return new BusinessException(
                CONFLICT,
                "APPLICATION_CODE_CONFLICT",
                "Application code already exists in this tenant"
        );
    }
}
