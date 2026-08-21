package com.doc.docquery.mapper;

import com.doc.docquery.dto.QueryAuditPageQueryDTO;
import com.doc.docquery.entity.ApplicationQueryAuditEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 应用查询审计的追加、终态更新、租户查询和保留期维护。 */
@Mapper
public interface ApplicationQueryAuditMapper {

    String SELECT_COLUMNS = """
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

    @Insert("""
            INSERT INTO application_query_audit (
                request_id, tenant_id, application_id, credential_id,
                credential_fingerprint, knowledge_base_id, operation_type,
                caller_trace_id, actor_ref, query_sha256, query_code_points,
                requested_mode, outcome, degraded, started_at, created_at, updated_at
            ) VALUES (
                #{requestId}, #{tenantId}, #{applicationId}, #{credentialId},
                #{credentialFingerprint}, #{knowledgeBaseId}, #{operationType},
                #{callerTraceId}, #{actorRef}, #{querySha256}, #{queryCodePoints},
                #{requestedMode}, #{outcome}, #{degraded}, #{startedAt},
                #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApplicationQueryAuditEntity entity);

    @Update("""
            UPDATE application_query_audit
            SET outcome = #{outcome}, http_status = #{httpStatus},
                failure_category = #{failureCategory}, failure_code = #{failureCode},
                query_execution_id = #{queryExecutionId},
                idempotency_disposition = #{idempotencyDisposition},
                snapshot_fingerprint = #{snapshotFingerprint},
                active_version_count = #{activeVersionCount},
                executed_mode = #{executedMode}, degraded = #{degraded},
                degradation_reason = #{degradationReason}, result_count = #{resultCount},
                evidence_count = #{evidenceCount}, answer_status = #{answerStatus},
                citation_count = #{citationCount}, tool_rounds = #{toolRounds},
                tool_calls = #{toolCalls}, model_calls = #{modelCalls},
                canonical_characters = #{canonicalCharacters},
                completed_at = #{completedAt}, duration_ms = #{durationMs},
                updated_at = #{updatedAt}
            WHERE id = #{id} AND outcome = '1'
            """)
    int finish(ApplicationQueryAuditEntity entity);

    @Select("SELECT " + SELECT_COLUMNS + """
             FROM application_query_audit
             WHERE tenant_id = #{tenantId} AND id = #{auditId}
            """)
    ApplicationQueryAuditEntity findByTenantAndId(
            @Param("tenantId") long tenantId,
            @Param("auditId") long auditId
    );

    @SelectProvider(type = ApplicationQueryAuditSqlProvider.class, method = "findPage")
    List<ApplicationQueryAuditEntity> findPage(QueryAuditPageQueryDTO query);

    @SelectProvider(type = ApplicationQueryAuditSqlProvider.class, method = "count")
    long count(QueryAuditPageQueryDTO query);

    @Update("""
            UPDATE application_query_audit
            SET outcome = '5', http_status = 503,
                failure_category = 'INTERRUPTED', failure_code = 'QUERY_INTERRUPTED',
                completed_at = #{now},
                duration_ms = TIMESTAMPDIFF(MICROSECOND, started_at, #{now}) DIV 1000,
                updated_at = #{now}
            WHERE outcome = '1' AND started_at < #{cutoff}
            ORDER BY id
            LIMIT #{limit}
            """)
    int markInterruptedBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Delete("""
            DELETE FROM application_query_audit
            WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM application_query_audit
                    WHERE started_at < #{cutoff} AND outcome <> '1'
                    ORDER BY id LIMIT #{limit}
                ) expired
            )
            """)
    int deleteTerminalBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );
}
