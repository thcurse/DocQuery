package com.doc.docquery.service.impl;

import com.doc.docquery.audit.QueryAuditOperation;
import com.doc.docquery.audit.QueryAuditOutcome;
import com.doc.docquery.audit.QueryIdempotencyDisposition;
import com.doc.docquery.dto.QueryAuditPageQueryDTO;
import com.doc.docquery.entity.ApplicationQueryAuditEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.ApplicationQueryAuditMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.QueryAuditManagementService;
import com.doc.docquery.vo.ApplicationQueryAuditVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/** 审计管理查询始终由可信Session Tenant范围约束。 */
@Service
public class QueryAuditManagementServiceImpl implements QueryAuditManagementService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();

    private final ApplicationQueryAuditMapper auditMapper;
    private final TenantMapper tenantMapper;

    public QueryAuditManagementServiceImpl(
            ApplicationQueryAuditMapper auditMapper,
            TenantMapper tenantMapper
    ) {
        this.auditMapper = auditMapper;
        this.tenantMapper = tenantMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<ApplicationQueryAuditVO> list(
            AdminPrincipal principal,
            long tenantId,
            QueryAuditPageQueryDTO query
    ) {
        requireScope(principal, tenantId);
        validate(query);
        query.setTenantId(tenantId);
        List<ApplicationQueryAuditVO> items = auditMapper.findPage(query).stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                Math.toIntExact(query.getOffset() / query.getLimit()),
                query.getLimit(),
                auditMapper.count(query)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationQueryAuditVO get(
            AdminPrincipal principal,
            long tenantId,
            long auditId
    ) {
        requireScope(principal, tenantId);
        if (auditId < 1) {
            throw validation("Audit ID must be positive");
        }
        ApplicationQueryAuditEntity entity = auditMapper.findByTenantAndId(tenantId, auditId);
        if (entity == null) {
            throw new BusinessException(
                    NOT_FOUND,
                    "QUERY_AUDIT_NOT_FOUND",
                    "Query audit was not found"
            );
        }
        return toVO(entity);
    }

    private void validate(QueryAuditPageQueryDTO query) {
        if (query == null || query.getLimit() < 1 || query.getLimit() > 100
                || query.getOffset() < 0) {
            throw validation("Audit pagination is invalid");
        }
        if (query.getFrom() != null && query.getTo() != null
                && !query.getFrom().isBefore(query.getTo())) {
            throw validation("Audit time range is invalid");
        }
        positive(query.getApplicationId(), "Application ID");
        positive(query.getKnowledgeBaseId(), "KnowledgeBase ID");
        if (query.getOperationType() != null) {
            try {
                query.setOperationType(QueryAuditOperation.valueOf(
                        query.getOperationType().strip().toUpperCase(Locale.ROOT)
                ).code());
            } catch (RuntimeException exception) {
                throw validation("Audit operation is invalid");
            }
        }
        if (query.getOutcome() != null) {
            try {
                query.setOutcome(QueryAuditOutcome.valueOf(
                        query.getOutcome().strip().toUpperCase(Locale.ROOT)
                ).code());
            } catch (RuntimeException exception) {
                throw validation("Audit outcome is invalid");
            }
        }
        uuid(query.getRequestId(), "Request ID");
        ascii(query.getQueryExecutionId(), 64, "Query execution ID");
        ascii(query.getCallerTraceId(), 128, "Caller trace ID");
    }

    private void requireScope(AdminPrincipal principal, long tenantId) {
        if (tenantId < 1 || principal == null || !TENANT_ADMIN.equals(principal.role())
                || principal.tenantId() == null
                || principal.tenantId().longValue() != tenantId) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_SCOPE_FORBIDDEN",
                    "Tenant is outside administrator scope"
            );
        }
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_NOT_ACTIVE",
                    "Tenant is not active"
            );
        }
    }

    private ApplicationQueryAuditVO toVO(ApplicationQueryAuditEntity entity) {
        QueryIdempotencyDisposition disposition = QueryIdempotencyDisposition.fromCode(
                entity.getIdempotencyDisposition()
        );
        return new ApplicationQueryAuditVO(
                entity.getId(), entity.getRequestId(), entity.getTenantId(),
                entity.getApplicationId(), entity.getCredentialId(),
                entity.getCredentialFingerprint(), entity.getKnowledgeBaseId(),
                QueryAuditOperation.fromCode(entity.getOperationType()).name(),
                entity.getCallerTraceId(), entity.getActorRef(), entity.getQuerySha256(),
                entity.getQueryCodePoints(), entity.getRequestedMode(),
                entity.getExecutedMode(), QueryAuditOutcome.fromCode(entity.getOutcome()).name(),
                entity.getHttpStatus(), entity.getFailureCategory(), entity.getFailureCode(),
                entity.getQueryExecutionId(), disposition == null ? null : disposition.name(),
                entity.getSnapshotFingerprint(), entity.getActiveVersionCount(),
                entity.isDegraded(), entity.getDegradationReason(), entity.getResultCount(),
                entity.getEvidenceCount(), entity.getAnswerStatus(), entity.getCitationCount(),
                entity.getToolRounds(), entity.getToolCalls(), entity.getModelCalls(),
                entity.getCanonicalCharacters(), entity.getStartedAt().atOffset(ZoneOffset.UTC),
                entity.getCompletedAt() == null ? null
                        : entity.getCompletedAt().atOffset(ZoneOffset.UTC),
                entity.getDurationMs()
        );
    }

    private void positive(Long value, String label) {
        if (value != null && value < 1) {
            throw validation(label + " must be positive");
        }
    }

    private void uuid(String value, String label) {
        if (value != null) {
            try {
                UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw validation(label + " is invalid");
            }
        }
    }

    private void ascii(String value, int max, String label) {
        if (value != null && (value.isBlank() || value.length() > max
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e))) {
            throw validation(label + " is invalid");
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }
}
