package com.doc.docquery.mapper;

import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 标准化文档对象清单的数据访问边界。 */
@Mapper
public interface DocumentCanonicalArtifactMapper {

    @Insert("""
            INSERT INTO document_canonical_artifact (
                tenant_id, document_version_id, schema_version, source_format,
                parser_name, parser_version, canonical_bucket, canonical_object_key,
                canonical_size_bytes, canonical_sha256, canonical_text_sha256,
                text_length, block_count, heading_count, warning_count, page_count,
                created_at
            ) VALUES (
                #{tenantId}, #{documentVersionId}, #{schemaVersion}, #{sourceFormat},
                #{parserName}, #{parserVersion}, #{canonicalBucket}, #{canonicalObjectKey},
                #{canonicalSizeBytes}, #{canonicalSha256}, #{canonicalTextSha256},
                #{textLength}, #{blockCount}, #{headingCount}, #{warningCount}, #{pageCount},
                #{createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentCanonicalArtifactEntity artifact);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   schema_version AS schemaVersion,
                   source_format AS sourceFormat,
                   parser_name AS parserName,
                   parser_version AS parserVersion,
                   canonical_bucket AS canonicalBucket,
                   canonical_object_key AS canonicalObjectKey,
                   canonical_size_bytes AS canonicalSizeBytes,
                   canonical_sha256 AS canonicalSha256,
                   canonical_text_sha256 AS canonicalTextSha256,
                   text_length AS textLength,
                   block_count AS blockCount,
                   heading_count AS headingCount,
                   warning_count AS warningCount,
                   page_count AS pageCount,
                   created_at AS createdAt
            FROM document_canonical_artifact
            WHERE document_version_id = #{documentVersionId}
            """)
    DocumentCanonicalArtifactEntity findByDocumentVersionId(
            @Param("documentVersionId") Long documentVersionId
    );

    @Select("""
            SELECT COUNT(*)
            FROM document_canonical_artifact
            WHERE canonical_bucket = #{bucket}
              AND canonical_object_key = #{objectKey}
            """)
    long countByCanonicalObject(
            @Param("bucket") String bucket,
            @Param("objectKey") String objectKey
    );

    @Delete({
            "<script>",
            "DELETE FROM document_canonical_artifact",
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
