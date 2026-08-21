package com.doc.docquery.service.impl;

import com.doc.docquery.dto.CreateKnowledgeBaseDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.dto.UpdateKnowledgeBaseDTO;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.KnowledgeBaseMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.KnowledgeBaseService;
import com.doc.docquery.vo.KnowledgeBaseVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/**
 * 租户知识库管理实现。
 *
 * <p>所有资源查找都携带 Tenant 条件，不存在、停用和跨租户资源按既定管理面
 * 契约返回稳定错误。</p>
 */
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String DISABLED = StatusCode.DISABLED.getCode();

    private final TenantMapper tenantMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseServiceImpl(
            TenantMapper tenantMapper,
            KnowledgeBaseMapper knowledgeBaseMapper
    ) {
        this.tenantMapper = tenantMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    @Transactional
    public KnowledgeBaseVO createKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            CreateKnowledgeBaseDTO dto
    ) {
        requireActiveTenantScope(principal, tenantId);
        String name = normalizeName(dto.getName());
        String description = normalizeDescription(dto.getDescription());
        if (knowledgeBaseMapper.countByTenantAndName(tenantId, name) != 0) {
            throw nameConflict();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity(
                null,
                tenantId,
                name,
                description,
                ACTIVE,
                now,
                now
        );
        try {
            if (knowledgeBaseMapper.insert(knowledgeBase) != 1
                    || knowledgeBase.getId() == null) {
                throw new IllegalStateException("KnowledgeBase was not created");
            }
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        return loadKnowledgeBase(tenantId, knowledgeBase.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<KnowledgeBaseVO> listKnowledgeBases(
            AdminPrincipal principal,
            long tenantId,
            PageQueryDTO query
    ) {
        requireActiveTenantScope(principal, tenantId);
        validatePage(query);
        long offset = (long) query.getPage() * query.getSize();
        List<KnowledgeBaseVO> items = knowledgeBaseMapper
                .findPageByTenant(tenantId, offset, query.getSize())
                .stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                query.getPage(),
                query.getSize(),
                knowledgeBaseMapper.countByTenant(tenantId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseVO getKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(knowledgeBaseId);
        return loadKnowledgeBase(tenantId, knowledgeBaseId);
    }

    @Override
    @Transactional
    public KnowledgeBaseVO updateKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            UpdateKnowledgeBaseDTO dto
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(knowledgeBaseId);
        if (dto.getName() == null
                && dto.getDescription() == null
                && dto.getStatus() == null) {
            throw validation("At least one KnowledgeBase field is required");
        }

        KnowledgeBaseEntity knowledgeBase = findKnowledgeBase(tenantId, knowledgeBaseId);
        if (knowledgeBase == null) {
            throw notFound();
        }

        String name = dto.getName() == null
                ? knowledgeBase.getName()
                : normalizeName(dto.getName());
        TenantResourceQueryDTO resourceQuery = new TenantResourceQueryDTO(
                tenantId,
                knowledgeBaseId
        );
        if (knowledgeBaseMapper.countByTenantAndNameExcludingId(resourceQuery, name) != 0) {
            throw nameConflict();
        }
        knowledgeBase.setName(name);
        if (dto.getDescription() != null) {
            knowledgeBase.setDescription(normalizeDescription(dto.getDescription()));
        }
        if (dto.getStatus() != null) {
            knowledgeBase.setStatus(validateStatus(dto.getStatus()));
        }
        knowledgeBase.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        try {
            if (knowledgeBaseMapper.update(knowledgeBase) != 1) {
                throw notFound();
            }
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        return loadKnowledgeBase(tenantId, knowledgeBaseId);
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

    private KnowledgeBaseVO loadKnowledgeBase(long tenantId, long knowledgeBaseId) {
        KnowledgeBaseEntity knowledgeBase = findKnowledgeBase(tenantId, knowledgeBaseId);
        if (knowledgeBase == null) {
            throw notFound();
        }
        return toVO(knowledgeBase);
    }

    private KnowledgeBaseEntity findKnowledgeBase(long tenantId, long knowledgeBaseId) {
        return knowledgeBaseMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, knowledgeBaseId)
        );
    }

    private KnowledgeBaseVO toVO(KnowledgeBaseEntity knowledgeBase) {
        return new KnowledgeBaseVO(
                knowledgeBase.getId(),
                knowledgeBase.getTenantId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getStatus(),
                knowledgeBase.getCreatedAt().atOffset(ZoneOffset.UTC),
                knowledgeBase.getUpdatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private String normalizeName(String requestedName) {
        if (requestedName == null) {
            throw validation("KnowledgeBase name is required");
        }
        String name = requestedName.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 200) {
            throw validation("KnowledgeBase name must contain between 1 and 200 characters");
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

    private void requirePositiveId(long knowledgeBaseId) {
        if (knowledgeBaseId < 1) {
            throw validation("KnowledgeBase ID must be positive");
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
                "KNOWLEDGE_BASE_NOT_FOUND",
                "KnowledgeBase was not found"
        );
    }

    private BusinessException nameConflict() {
        return new BusinessException(
                CONFLICT,
                "KNOWLEDGE_BASE_NAME_CONFLICT",
                "KnowledgeBase name already exists in this tenant"
        );
    }
}
