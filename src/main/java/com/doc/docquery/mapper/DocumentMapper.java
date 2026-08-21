package com.doc.docquery.mapper;

import com.doc.docquery.dto.ActiveDocumentVersionDTO;
import com.doc.docquery.dto.DocumentManagementPageQueryDTO;
import com.doc.docquery.entity.DocumentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 逻辑文档数据访问边界；所有读写都显式携带 Tenant 隔离键。 */
@Mapper
public interface DocumentMapper {

    @Insert("""
            INSERT INTO document (
                tenant_id, knowledge_base_id, name, active_name, status,
                active_version_id, latest_version_id,
                deletion_requested_at, deleted_at, created_by_admin_id,
                created_at, updated_at
            ) VALUES (
                #{tenantId}, #{knowledgeBaseId}, #{name}, #{activeName}, #{status},
                #{activeVersionId}, #{latestVersionId},
                #{deletionRequestedAt}, #{deletedAt}, #{createdByAdminId},
                #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentEntity document);

    @Select("""
            SELECT COUNT(*)
            FROM document
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND active_name = #{name}
            """)
    long countByTenantKnowledgeBaseAndName(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("name") String name
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   knowledge_base_id AS knowledgeBaseId,
                   name,
                   active_name AS activeName,
                   status,
                   active_version_id AS activeVersionId,
                   latest_version_id AS latestVersionId,
                   deletion_requested_at AS deletionRequestedAt,
                   deleted_at AS deletedAt,
                   created_by_admin_id AS createdByAdminId,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND id = #{documentId}
            """)
    DocumentEntity findByTenantKnowledgeBaseAndId(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentId") Long documentId
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   knowledge_base_id AS knowledgeBaseId,
                   name,
                   active_name AS activeName,
                   status,
                   active_version_id AS activeVersionId,
                   latest_version_id AS latestVersionId,
                   deletion_requested_at AS deletionRequestedAt,
                   deleted_at AS deletedAt,
                   created_by_admin_id AS createdByAdminId,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND id = #{documentId}
            FOR UPDATE
            """)
    /**
     * 锁定逻辑文档行以串行化版本号分配，并保证同一文档最多一个处理中版本。
     */
    DocumentEntity findByTenantKnowledgeBaseAndIdForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentId") Long documentId
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   knowledge_base_id AS knowledgeBaseId,
                   name,
                   active_name AS activeName,
                   status,
                   active_version_id AS activeVersionId,
                   latest_version_id AS latestVersionId,
                   deletion_requested_at AS deletionRequestedAt,
                   deleted_at AS deletedAt,
                   created_by_admin_id AS createdByAdminId,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document
            WHERE tenant_id = #{tenantId} AND id = #{documentId}
            """)
    DocumentEntity findByTenantAndId(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId
    );

    @Select("""
            SELECT d.id AS documentId,
                   d.active_version_id AS documentVersionId,
                   v.version_no AS versionNo,
                   d.name AS documentName
            FROM document d
            INNER JOIN document_version v
                    ON v.id = d.active_version_id
                   AND v.tenant_id = d.tenant_id
                   AND v.document_id = d.id
                   AND v.status = #{readyVersionStatus}
            WHERE d.tenant_id = #{tenantId}
              AND d.knowledge_base_id = #{knowledgeBaseId}
              AND d.status = #{activeDocumentStatus}
              AND d.active_version_id IS NOT NULL
            ORDER BY d.id
            """)
    /**
     * 用一条一致性读取形成知识库当前 activeVersion 快照，避免双路查询分别读版本。
     */
    List<ActiveDocumentVersionDTO> findActiveVersionSnapshot(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("activeDocumentStatus") String activeDocumentStatus,
            @Param("readyVersionStatus") String readyVersionStatus
    );

    @SelectProvider(type = DocumentManagementSqlProvider.class, method = "findPage")
    List<DocumentEntity> findManagementPage(DocumentManagementPageQueryDTO query);

    @SelectProvider(type = DocumentManagementSqlProvider.class, method = "count")
    long countManagementPage(DocumentManagementPageQueryDTO query);

    @Update("""
            UPDATE document
            SET latest_version_id = #{latestVersionId},
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId} AND id = #{documentId}
            """)
    /** 只推进最新受理版本指针；N2.1 不修改当前可检索版本指针。 */
    int updateLatestVersion(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("latestVersionId") Long latestVersionId,
            @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    @Update("""
            UPDATE document
            SET active_version_id = #{versionId},
                updated_at = #{now}
            WHERE id = #{documentId}
              AND tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND latest_version_id = #{versionId}
              AND status = #{activeStatus}
            """)
    /** 只允许把仍是 latest 的 READY 候选切换为当前可检索版本。 */
    int activateLatestVersion(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentId") Long documentId,
            @Param("versionId") Long versionId,
            @Param("activeStatus") String activeStatus,
            @Param("now") java.time.LocalDateTime now
    );

    @Update("""
            UPDATE document
            SET status = #{deletingStatus},
                active_version_id = NULL,
                deletion_requested_at = #{now},
                deleted_at = NULL,
                updated_at = #{now}
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND id = #{documentId}
              AND status = #{activeStatus}
            """)
    int beginDeletion(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentId") Long documentId,
            @Param("activeStatus") String activeStatus,
            @Param("deletingStatus") String deletingStatus,
            @Param("now") java.time.LocalDateTime now
    );

    @Update("""
            UPDATE document
            SET status = #{deletedStatus},
                active_name = NULL,
                active_version_id = NULL,
                deleted_at = #{now},
                updated_at = #{now}
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND id = #{documentId}
              AND status = #{deletingStatus}
            """)
    int completeDeletion(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentId") Long documentId,
            @Param("deletingStatus") String deletingStatus,
            @Param("deletedStatus") String deletedStatus,
            @Param("now") java.time.LocalDateTime now
    );
}
