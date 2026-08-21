package com.doc.docquery.mapper;

import com.doc.docquery.dto.QueryAuditPageQueryDTO;

/** 为审计列表生成有限、显式的筛选SQL。 */
public final class ApplicationQueryAuditSqlProvider {

    private static final String COLUMNS = """
            id, request_id AS requestId, tenant_id AS tenantId,
            application_id AS applicationId, credential_id AS credentialId,
            credential_fingerprint AS credentialFingerprint,
            knowledge_base_id AS knowledgeBaseId, operation_type AS operationType,
            caller_trace_id AS callerTraceId, actor_ref AS actorRef,
            query_sha256 AS querySha256, query_code_points AS queryCodePoints,
            requested_mode AS requestedMode, executed_mode AS executedMode,
            outcome, http_status AS httpStatus, failure_category AS failureCategory,
            failure_code AS failureCode, query_execution_id AS queryExecutionId,
            idempotency_disposition AS idempotencyDisposition,
            snapshot_fingerprint AS snapshotFingerprint,
            active_version_count AS activeVersionCount, degraded,
            degradation_reason AS degradationReason, result_count AS resultCount,
            evidence_count AS evidenceCount, answer_status AS answerStatus,
            citation_count AS citationCount, tool_rounds AS toolRounds,
            tool_calls AS toolCalls, model_calls AS modelCalls,
            canonical_characters AS canonicalCharacters,
            started_at AS startedAt, completed_at AS completedAt,
            duration_ms AS durationMs, created_at AS createdAt, updated_at AS updatedAt
            """;

    public String findPage(QueryAuditPageQueryDTO query) {
        return "SELECT " + COLUMNS + " FROM application_query_audit"
                + where(query)
                + " ORDER BY started_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}";
    }

    public String count(QueryAuditPageQueryDTO query) {
        return "SELECT COUNT(*) FROM application_query_audit" + where(query);
    }

    private String where(QueryAuditPageQueryDTO query) {
        StringBuilder sql = new StringBuilder(" WHERE tenant_id = #{tenantId}");
        if (query.getFrom() != null) {
            sql.append(" AND started_at >= #{from}");
        }
        if (query.getTo() != null) {
            sql.append(" AND started_at < #{to}");
        }
        if (query.getApplicationId() != null) {
            sql.append(" AND application_id = #{applicationId}");
        }
        if (query.getKnowledgeBaseId() != null) {
            sql.append(" AND knowledge_base_id = #{knowledgeBaseId}");
        }
        if (query.getOperationType() != null) {
            sql.append(" AND operation_type = #{operationType}");
        }
        if (query.getOutcome() != null) {
            sql.append(" AND outcome = #{outcome}");
        }
        if (query.getRequestId() != null) {
            sql.append(" AND request_id = #{requestId}");
        }
        if (query.getQueryExecutionId() != null) {
            sql.append(" AND query_execution_id = #{queryExecutionId}");
        }
        if (query.getCallerTraceId() != null) {
            sql.append(" AND caller_trace_id = #{callerTraceId}");
        }
        return sql.toString();
    }
}
