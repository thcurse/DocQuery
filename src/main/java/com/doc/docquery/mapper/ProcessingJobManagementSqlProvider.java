package com.doc.docquery.mapper;

import com.doc.docquery.dto.ProcessingJobManagementPageQueryDTO;

/** 为管理面处理任务列表生成有限、显式的筛选 SQL。 */
public final class ProcessingJobManagementSqlProvider {

    static final String COLUMNS = """
            p.id, p.tenant_id AS tenantId,
            d.knowledge_base_id AS knowledgeBaseId,
            kb.name AS knowledgeBaseName,
            d.id AS documentId, d.name AS documentName,
            v.id AS documentVersionId, v.version_no AS versionNo,
            p.job_type AS jobType, p.attempt_no AS attemptNo, p.status,
            p.failure_code AS failureCode, p.failure_message AS failureMessage,
            p.failure_retryable AS failureRetryable,
            p.started_at AS startedAt, p.finished_at AS finishedAt,
            p.created_at AS createdAt, p.updated_at AS updatedAt
            """;

    static final String JOINS = """
             FROM processing_job p
             INNER JOIN document_version v
                     ON v.id = p.document_version_id
                    AND v.tenant_id = p.tenant_id
             INNER JOIN document d
                     ON d.id = v.document_id
                    AND d.tenant_id = p.tenant_id
             INNER JOIN knowledge_base kb
                     ON kb.id = d.knowledge_base_id
                    AND kb.tenant_id = p.tenant_id
            """;

    public String findPage(ProcessingJobManagementPageQueryDTO query) {
        return "SELECT " + COLUMNS + JOINS + where(query)
                + " ORDER BY p.updated_at DESC, p.id DESC LIMIT #{limit} OFFSET #{offset}";
    }

    public String count(ProcessingJobManagementPageQueryDTO query) {
        return "SELECT COUNT(*)" + JOINS + where(query);
    }

    private String where(ProcessingJobManagementPageQueryDTO query) {
        StringBuilder sql = new StringBuilder(" WHERE p.tenant_id = #{tenantId}");
        if (query.getKnowledgeBaseId() != null) {
            sql.append(" AND d.knowledge_base_id = #{knowledgeBaseId}");
        }
        if (query.getDocumentId() != null) {
            sql.append(" AND d.id = #{documentId}");
        }
        if (query.getDocumentVersionId() != null) {
            sql.append(" AND v.id = #{documentVersionId}");
        }
        if (query.getStatus() != null) {
            sql.append(" AND p.status = #{status}");
        }
        if (query.getFrom() != null) {
            sql.append(" AND p.created_at >= #{from}");
        }
        if (query.getTo() != null) {
            sql.append(" AND p.created_at < #{to}");
        }
        return sql.toString();
    }
}
