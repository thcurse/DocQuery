package com.doc.docquery.service.impl;

import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.dto.UpsertApplicationGrantDTO;
import com.doc.docquery.entity.ApplicationEntity;
import com.doc.docquery.entity.ApplicationGrantEntity;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.GrantPermission;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.ApplicationGrantMapper;
import com.doc.docquery.mapper.ApplicationMapper;
import com.doc.docquery.mapper.KnowledgeBaseMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.ApplicationGrantService;
import com.doc.docquery.vo.ApplicationGrantVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/**
 * 应用知识库授权管理实现。
 *
 * <p>所有变更要求同租户的有效租户管理员，并锁定应用行串行化授权写入；
 * 撤销保留原记录和操作人，恢复时复用同一授权事实。</p>
 */
@Service
public class ApplicationGrantServiceImpl implements ApplicationGrantService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String REVOKED = StatusCode.REVOKED.getCode();

    private final TenantMapper tenantMapper;
    private final ApplicationMapper applicationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ApplicationGrantMapper applicationGrantMapper;

    public ApplicationGrantServiceImpl(
            TenantMapper tenantMapper,
            ApplicationMapper applicationMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            ApplicationGrantMapper applicationGrantMapper
    ) {
        this.tenantMapper = tenantMapper;
        this.applicationMapper = applicationMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.applicationGrantMapper = applicationGrantMapper;
    }

    @Override
    @Transactional
    public ApplicationGrantVO upsertGrant(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            long knowledgeBaseId,
            UpsertApplicationGrantDTO dto
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId, "Application ID must be positive");
        requirePositiveId(knowledgeBaseId, "KnowledgeBase ID must be positive");
        String permission = validatePermission(dto.getPermission());

        // 锁定应用行，使同一应用的授权写入顺序稳定，并与凭证创建保持一致。
        ApplicationEntity application = findApplicationForUpdate(tenantId, applicationId);
        if (application == null) {
            throw applicationNotFound();
        }
        KnowledgeBaseEntity knowledgeBase = findKnowledgeBase(tenantId, knowledgeBaseId);
        if (knowledgeBase == null) {
            throw knowledgeBaseNotFound();
        }
        if (!ACTIVE.equals(application.getStatus())) {
            throw conflict(
                    "APPLICATION_NOT_ACTIVE",
                    "Application must be active to grant access"
            );
        }
        if (!ACTIVE.equals(knowledgeBase.getStatus())) {
            throw conflict(
                    "KNOWLEDGE_BASE_NOT_ACTIVE",
                    "KnowledgeBase must be active to grant access"
            );
        }

        ApplicationGrantEntity grant = applicationGrantMapper.find(
                tenantId,
                applicationId,
                knowledgeBaseId
        );
        // 相同有效授权是幂等操作，不刷新审计时间或制造无意义更新。
        if (grant != null
                && ACTIVE.equals(grant.getStatus())
                && permission.equals(grant.getPermission())) {
            return toVO(grant);
        }

        LocalDateTime now = nowUtc();
        if (grant == null) {
            grant = new ApplicationGrantEntity(
                    null,
                    tenantId,
                    applicationId,
                    knowledgeBaseId,
                    permission,
                    ACTIVE,
                    now,
                    principal.id(),
                    null,
                    null
            );
            if (applicationGrantMapper.insert(grant) != 1 || grant.getId() == null) {
                throw new IllegalStateException("Application grant was not created");
            }
            return toVO(grant);
        }

        // 已撤销授权通过更新原记录恢复，保留同一关系的历史身份。
        grant.setPermission(permission);
        grant.setStatus(ACTIVE);
        grant.setGrantedAt(now);
        grant.setGrantedBy(principal.id());
        grant.setRevokedAt(null);
        grant.setRevokedBy(null);
        if (applicationGrantMapper.update(grant) != 1) {
            throw new IllegalStateException("Application grant was not updated");
        }
        return toVO(grant);
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<ApplicationGrantVO> listByApplication(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            PageQueryDTO query
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId, "Application ID must be positive");
        validatePage(query);
        if (findApplication(tenantId, applicationId) == null) {
            throw applicationNotFound();
        }
        long offset = (long) query.getPage() * query.getSize();
        List<ApplicationGrantVO> items = applicationGrantMapper
                .findPageByApplication(tenantId, applicationId, offset, query.getSize())
                .stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                query.getPage(),
                query.getSize(),
                applicationGrantMapper.countByApplication(tenantId, applicationId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<ApplicationGrantVO> listByKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            PageQueryDTO query
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(knowledgeBaseId, "KnowledgeBase ID must be positive");
        validatePage(query);
        if (findKnowledgeBase(tenantId, knowledgeBaseId) == null) {
            throw knowledgeBaseNotFound();
        }
        long offset = (long) query.getPage() * query.getSize();
        List<ApplicationGrantVO> items = applicationGrantMapper
                .findPageByKnowledgeBase(
                        tenantId,
                        knowledgeBaseId,
                        offset,
                        query.getSize()
                )
                .stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                query.getPage(),
                query.getSize(),
                applicationGrantMapper.countByKnowledgeBase(tenantId, knowledgeBaseId)
        );
    }

    @Override
    @Transactional
    public void revokeGrant(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            long knowledgeBaseId
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId, "Application ID must be positive");
        requirePositiveId(knowledgeBaseId, "KnowledgeBase ID must be positive");
        if (findApplicationForUpdate(tenantId, applicationId) == null) {
            throw applicationNotFound();
        }
        if (findKnowledgeBase(tenantId, knowledgeBaseId) == null) {
            throw knowledgeBaseNotFound();
        }

        ApplicationGrantEntity grant = applicationGrantMapper.find(
                tenantId,
                applicationId,
                knowledgeBaseId
        );
        // 重复撤销按幂等成功处理。
        if (grant == null || REVOKED.equals(grant.getStatus())) {
            return;
        }
        if (!ACTIVE.equals(grant.getStatus())) {
            throw new IllegalStateException("Application grant status is invalid");
        }
        applicationGrantMapper.revokeIfActive(
                tenantId,
                applicationId,
                knowledgeBaseId,
                nowUtc(),
                principal.id(),
                ACTIVE,
                REVOKED
        );
    }

    private void requireActiveTenantScope(AdminPrincipal principal, long tenantId) {
        requirePositiveId(tenantId, "Tenant ID must be positive");
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

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private ApplicationEntity findApplication(long tenantId, long applicationId) {
        return applicationMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, applicationId)
        );
    }

    private ApplicationEntity findApplicationForUpdate(long tenantId, long applicationId) {
        return applicationMapper.findByTenantAndIdForUpdate(
                new TenantResourceQueryDTO(tenantId, applicationId)
        );
    }

    private KnowledgeBaseEntity findKnowledgeBase(long tenantId, long knowledgeBaseId) {
        return knowledgeBaseMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, knowledgeBaseId)
        );
    }

    private String validatePermission(String permission) {
        try {
            return GrantPermission.fromCode(permission).getCode();
        } catch (IllegalArgumentException exception) {
            throw validation("Permission must be 1 (READ), 2 (WRITE) or 3 (READ_WRITE)");
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

    private void requirePositiveId(long id, String message) {
        if (id < 1) {
            throw validation(message);
        }
    }

    private ApplicationGrantVO toVO(ApplicationGrantEntity grant) {
        return new ApplicationGrantVO(
                grant.getId(),
                grant.getTenantId(),
                grant.getApplicationId(),
                grant.getKnowledgeBaseId(),
                grant.getPermission(),
                grant.getStatus(),
                grant.getGrantedAt().atOffset(ZoneOffset.UTC),
                grant.getGrantedBy(),
                grant.getRevokedAt() == null
                        ? null
                        : grant.getRevokedAt().atOffset(ZoneOffset.UTC),
                grant.getRevokedBy()
        );
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException forbidden(String code, String message) {
        return new BusinessException(FORBIDDEN, code, message);
    }

    private BusinessException applicationNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "APPLICATION_NOT_FOUND",
                "Application was not found"
        );
    }

    private BusinessException knowledgeBaseNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "KNOWLEDGE_BASE_NOT_FOUND",
                "KnowledgeBase was not found"
        );
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(CONFLICT, code, message);
    }
}
