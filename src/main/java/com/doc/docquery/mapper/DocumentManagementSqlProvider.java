package com.doc.docquery.mapper;

import com.doc.docquery.dto.DocumentManagementPageQueryDTO;

/** 为管理面文档列表生成有限、显式的筛选 SQL。 */
public final class DocumentManagementSqlProvider {

    private static final String COLUMNS = """
            d.id, d.tenant_id AS tenantId, d.knowledge_base_id AS knowledgeBaseId,
            d.name, d.active_name AS activeName, d.status,
            d.active_version_id AS activeVersionId,
            d.latest_version_id AS latestVersionId,
            d.deletion_requested_at AS deletionRequestedAt,
            d.deleted_at AS deletedAt,
            d.created_by_admin_id AS createdByAdminId,
            d.created_at AS createdAt, d.updated_at AS updatedAt
            """;

    public String findPage(DocumentManagementPageQueryDTO query) {
        return "SELECT " + COLUMNS + fromAndWhere(query)
                + " ORDER BY d.updated_at DESC, d.id DESC LIMIT #{limit} OFFSET #{offset}";
    }

    public String count(DocumentManagementPageQueryDTO query) {
        return "SELECT COUNT(*)" + fromAndWhere(query);
    }

    private String fromAndWhere(DocumentManagementPageQueryDTO query) {
        StringBuilder sql = new StringBuilder("""
                 FROM document d
                 INNER JOIN document_version latest
                         ON latest.id = d.latest_version_id
                        AND latest.tenant_id = d.tenant_id
                        AND latest.document_id = d.id
                 WHERE d.tenant_id = #{tenantId}
                   AND d.knowledge_base_id = #{knowledgeBaseId}
                """);
        if (query.getNamePattern() != null) {
            sql.append(" AND d.name LIKE CONCAT('%', #{namePattern}, '%') ESCAPE '\\\\'");
        }
        if (query.getDocumentStatus() != null) {
            sql.append(" AND d.status = #{documentStatus}");
        }
        if (query.getLatestVersionStatus() != null) {
            sql.append(" AND latest.status = #{latestVersionStatus}");
        }
        return sql.toString();
    }
}
