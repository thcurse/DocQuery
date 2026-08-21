package com.doc.docquery.mapper;

import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** N2.4 retrieval.jsonl 清单的数据访问边界。 */
@Mapper
public interface DocumentRetrievalArtifactMapper {

    @Insert("""
            INSERT INTO document_retrieval_artifact (
                tenant_id, document_version_id, canonical_artifact_id, schema_version,
                chat_provider, chat_model, chat_prompt_version, thinking_mode,
                embedding_provider, embedding_model, embedding_dimension,
                embedding_template_version, generation_fingerprint, retrieval_bucket,
                retrieval_object_key, retrieval_size_bytes, retrieval_sha256,
                semantic_sha256, vector_sha256, profile_count, node_count, vector_count,
                created_at, updated_at
            ) VALUES (
                #{tenantId}, #{documentVersionId}, #{canonicalArtifactId}, #{schemaVersion},
                #{chatProvider}, #{chatModel}, #{chatPromptVersion}, #{thinkingMode},
                #{embeddingProvider}, #{embeddingModel}, #{embeddingDimension},
                #{embeddingTemplateVersion}, #{generationFingerprint}, #{retrievalBucket},
                #{retrievalObjectKey}, #{retrievalSizeBytes}, #{retrievalSha256},
                #{semanticSha256}, #{vectorSha256}, #{profileCount}, #{nodeCount},
                #{vectorCount}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentRetrievalArtifactEntity artifact);

    @Select("""
            SELECT id, tenant_id AS tenantId, document_version_id AS documentVersionId,
                   canonical_artifact_id AS canonicalArtifactId, schema_version AS schemaVersion,
                   chat_provider AS chatProvider, chat_model AS chatModel,
                   chat_prompt_version AS chatPromptVersion, thinking_mode AS thinkingMode,
                   embedding_provider AS embeddingProvider,
                   embedding_model AS embeddingModel, embedding_dimension AS embeddingDimension,
                   embedding_template_version AS embeddingTemplateVersion,
                   generation_fingerprint AS generationFingerprint,
                   retrieval_bucket AS retrievalBucket, retrieval_object_key AS retrievalObjectKey,
                   retrieval_size_bytes AS retrievalSizeBytes,
                   retrieval_sha256 AS retrievalSha256, semantic_sha256 AS semanticSha256,
                   vector_sha256 AS vectorSha256, profile_count AS profileCount,
                   node_count AS nodeCount, vector_count AS vectorCount,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_retrieval_artifact
            WHERE document_version_id = #{documentVersionId}
            """)
    DocumentRetrievalArtifactEntity findByDocumentVersionId(
            @Param("documentVersionId") Long documentVersionId
    );

    @Select("""
            SELECT COUNT(*) FROM document_retrieval_artifact
            WHERE retrieval_bucket = #{bucket} AND retrieval_object_key = #{objectKey}
            """)
    long countByRetrievalObject(
            @Param("bucket") String bucket,
            @Param("objectKey") String objectKey
    );

    @Delete({
            "<script>",
            "DELETE FROM document_retrieval_artifact",
            "WHERE tenant_id = #{tenantId} AND document_version_id IN",
            "<foreach collection='versionIds' item='versionId' open='(' separator=',' close=')'>",
            "#{versionId}",
            "</foreach>",
            "</script>"
    })
    int deleteByTenantAndVersions(
            @Param("tenantId") Long tenantId,
            @Param("versionIds") List<Long> versionIds
    );
}
