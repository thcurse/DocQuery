package com.doc.docquery.mapper;

import com.doc.docquery.entity.DocumentSearchProjectionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** N2.5 Elasticsearch 双投影验收单的数据访问边界。 */
@Mapper
public interface DocumentSearchProjectionMapper {

    /**
     * 写入或更新同一 DocumentVersion 的最近有效投影。
     *
     * <p>ES Cluster 丢失后允许从不可变对象受控重建，因此清单不是追加历史表；
     * created_at 保留首次时间，其他字段在完整复核后原子替换。</p>
     */
    @Insert("""
            INSERT INTO document_search_projection (
                tenant_id, knowledge_base_id, document_id, document_version_id,
                canonical_artifact_id, canonical_artifact_sha256,
                retrieval_artifact_id, retrieval_artifact_sha256,
                cluster_uuid, evidence_index_name, evidence_index_uuid,
                evidence_mapping_version, evidence_expected_count, evidence_actual_count,
                navigation_index_name, navigation_index_uuid,
                navigation_mapping_version, navigation_expected_count,
                navigation_actual_count, projection_fingerprint,
                completed_at, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{knowledgeBaseId}, #{documentId}, #{documentVersionId},
                #{canonicalArtifactId}, #{canonicalArtifactSha256},
                #{retrievalArtifactId}, #{retrievalArtifactSha256},
                #{clusterUuid}, #{evidenceIndexName}, #{evidenceIndexUuid},
                #{evidenceMappingVersion}, #{evidenceExpectedCount}, #{evidenceActualCount},
                #{navigationIndexName}, #{navigationIndexUuid},
                #{navigationMappingVersion}, #{navigationExpectedCount},
                #{navigationActualCount}, #{projectionFingerprint},
                #{completedAt}, #{createdAt}, #{updatedAt}
            ) AS incoming
            ON DUPLICATE KEY UPDATE
                tenant_id = incoming.tenant_id,
                knowledge_base_id = incoming.knowledge_base_id,
                document_id = incoming.document_id,
                canonical_artifact_id = incoming.canonical_artifact_id,
                canonical_artifact_sha256 = incoming.canonical_artifact_sha256,
                retrieval_artifact_id = incoming.retrieval_artifact_id,
                retrieval_artifact_sha256 = incoming.retrieval_artifact_sha256,
                cluster_uuid = incoming.cluster_uuid,
                evidence_index_name = incoming.evidence_index_name,
                evidence_index_uuid = incoming.evidence_index_uuid,
                evidence_mapping_version = incoming.evidence_mapping_version,
                evidence_expected_count = incoming.evidence_expected_count,
                evidence_actual_count = incoming.evidence_actual_count,
                navigation_index_name = incoming.navigation_index_name,
                navigation_index_uuid = incoming.navigation_index_uuid,
                navigation_mapping_version = incoming.navigation_mapping_version,
                navigation_expected_count = incoming.navigation_expected_count,
                navigation_actual_count = incoming.navigation_actual_count,
                projection_fingerprint = incoming.projection_fingerprint,
                completed_at = incoming.completed_at,
                updated_at = incoming.updated_at
            """)
    int upsert(DocumentSearchProjectionEntity projection);

    @Select("""
            SELECT id, tenant_id AS tenantId, knowledge_base_id AS knowledgeBaseId,
                   document_id AS documentId, document_version_id AS documentVersionId,
                   canonical_artifact_id AS canonicalArtifactId,
                   canonical_artifact_sha256 AS canonicalArtifactSha256,
                   retrieval_artifact_id AS retrievalArtifactId,
                   retrieval_artifact_sha256 AS retrievalArtifactSha256,
                   cluster_uuid AS clusterUuid,
                   evidence_index_name AS evidenceIndexName,
                   evidence_index_uuid AS evidenceIndexUuid,
                   evidence_mapping_version AS evidenceMappingVersion,
                   evidence_expected_count AS evidenceExpectedCount,
                   evidence_actual_count AS evidenceActualCount,
                   navigation_index_name AS navigationIndexName,
                   navigation_index_uuid AS navigationIndexUuid,
                   navigation_mapping_version AS navigationMappingVersion,
                   navigation_expected_count AS navigationExpectedCount,
                   navigation_actual_count AS navigationActualCount,
                   projection_fingerprint AS projectionFingerprint,
                   completed_at AS completedAt, created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document_search_projection
            WHERE document_version_id = #{documentVersionId}
            """)
    DocumentSearchProjectionEntity findByDocumentVersionId(
            @Param("documentVersionId") Long documentVersionId
    );

    @Select("""
            SELECT id, tenant_id AS tenantId, knowledge_base_id AS knowledgeBaseId,
                   document_id AS documentId, document_version_id AS documentVersionId,
                   canonical_artifact_id AS canonicalArtifactId,
                   canonical_artifact_sha256 AS canonicalArtifactSha256,
                   retrieval_artifact_id AS retrievalArtifactId,
                   retrieval_artifact_sha256 AS retrievalArtifactSha256,
                   cluster_uuid AS clusterUuid,
                   evidence_index_name AS evidenceIndexName,
                   evidence_index_uuid AS evidenceIndexUuid,
                   evidence_mapping_version AS evidenceMappingVersion,
                   evidence_expected_count AS evidenceExpectedCount,
                   evidence_actual_count AS evidenceActualCount,
                   navigation_index_name AS navigationIndexName,
                   navigation_index_uuid AS navigationIndexUuid,
                   navigation_mapping_version AS navigationMappingVersion,
                   navigation_expected_count AS navigationExpectedCount,
                   navigation_actual_count AS navigationActualCount,
                   projection_fingerprint AS projectionFingerprint,
                   completed_at AS completedAt, created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document_search_projection
            WHERE document_version_id = #{documentVersionId}
            FOR UPDATE
            """)
    DocumentSearchProjectionEntity findByDocumentVersionIdForUpdate(
            @Param("documentVersionId") Long documentVersionId
    );

    @Delete("""
            DELETE FROM document_search_projection
            WHERE tenant_id = #{tenantId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND document_id = #{documentId}
            """)
    int deleteByDocument(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("documentId") Long documentId
    );
}
