package com.doc.docquery.service.impl;

import com.doc.docquery.audit.QueryAuditFailure;
import com.doc.docquery.audit.QueryAuditOutcome;
import com.doc.docquery.audit.QueryAuditStart;
import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.entity.ApplicationQueryAuditEntity;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.ApplicationQueryAuditMapper;
import com.doc.docquery.mapper.CredentialMapper;
import com.doc.docquery.service.QueryAuditService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.doc.docquery.exception.BusinessException.Failure.UNAVAILABLE;

/** 每个审计阶段使用独立短事务，避免把查询依赖调用包进数据库事务。 */
@Service
public class QueryAuditServiceImpl implements QueryAuditService {

    private final ApplicationQueryAuditMapper auditMapper;
    private final CredentialMapper credentialMapper;

    public QueryAuditServiceImpl(
            ApplicationQueryAuditMapper auditMapper,
            CredentialMapper credentialMapper
    ) {
        this.auditMapper = auditMapper;
        this.credentialMapper = credentialMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplicationQueryAuditEntity start(QueryAuditStart start) {
        LocalDateTime now = nowUtc();
        ApplicationQueryAuditEntity entity = new ApplicationQueryAuditEntity();
        entity.setRequestId(start.requestId());
        entity.setTenantId(start.principal().getTenantId());
        entity.setApplicationId(start.principal().getApplicationId());
        entity.setCredentialId(start.principal().getCredentialId());
        entity.setCredentialFingerprint(start.credentialFingerprint());
        entity.setKnowledgeBaseId(start.knowledgeBaseId());
        entity.setOperationType(start.operation().code());
        entity.setCallerTraceId(start.callerTraceId());
        entity.setActorRef(start.actorRef());
        entity.setQuerySha256(start.querySha256());
        entity.setQueryCodePoints(start.queryCodePoints());
        entity.setRequestedMode(start.requestedMode());
        entity.setOutcome(QueryAuditOutcome.STARTED.code());
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            if (auditMapper.insert(entity) != 1 || entity.getId() == null) {
                throw unavailable(null);
            }
            return entity;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(
            ApplicationQueryAuditEntity audit,
            QueryExecutionTelemetry telemetry
    ) {
        finish(audit, telemetry, QueryAuditOutcome.SUCCEEDED, 200, null, null, true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            ApplicationQueryAuditEntity audit,
            QueryExecutionTelemetry telemetry,
            QueryAuditFailure failure
    ) {
        finish(
                audit,
                telemetry,
                failure.outcome(),
                failure.httpStatus(),
                failure.category(),
                failure.code(),
                false
        );
    }

    private void finish(
            ApplicationQueryAuditEntity audit,
            QueryExecutionTelemetry telemetry,
            QueryAuditOutcome outcome,
            int httpStatus,
            String category,
            String code,
            boolean successfulUse
    ) {
        LocalDateTime completed = nowUtc();
        audit.setOutcome(outcome.code());
        audit.setHttpStatus(httpStatus);
        audit.setFailureCategory(category);
        audit.setFailureCode(code);
        audit.setQueryExecutionId(telemetry.getQueryExecutionId());
        audit.setIdempotencyDisposition(telemetry.getIdempotencyDisposition());
        audit.setSnapshotFingerprint(telemetry.getSnapshotFingerprint());
        audit.setActiveVersionCount(telemetry.getActiveVersionCount());
        audit.setExecutedMode(telemetry.getExecutedMode());
        audit.setDegraded(telemetry.isDegraded());
        audit.setDegradationReason(telemetry.getDegradationReason());
        audit.setResultCount(telemetry.getResultCount());
        audit.setEvidenceCount(telemetry.getEvidenceCount());
        audit.setAnswerStatus(telemetry.getAnswerStatus());
        audit.setCitationCount(telemetry.getCitationCount());
        audit.setToolRounds(telemetry.getToolRounds());
        audit.setToolCalls(telemetry.getToolCalls());
        audit.setModelCalls(telemetry.getModelCalls());
        audit.setCanonicalCharacters(telemetry.getCanonicalCharacters());
        audit.setCompletedAt(completed);
        audit.setDurationMs(Math.max(
                0L,
                Duration.between(audit.getStartedAt(), completed).toMillis()
        ));
        audit.setUpdatedAt(completed);
        try {
            if (auditMapper.finish(audit) != 1) {
                throw unavailable(null);
            }
            if (successfulUse) {
                credentialMapper.touchLastUsedAt(audit.getCredentialId(), completed);
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private BusinessException unavailable(Throwable cause) {
        BusinessException exception = new BusinessException(
                UNAVAILABLE,
                "QUERY_AUDIT_UNAVAILABLE",
                "Query audit is unavailable"
        );
        if (cause != null) {
            exception.addSuppressed(cause);
        }
        return exception;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
