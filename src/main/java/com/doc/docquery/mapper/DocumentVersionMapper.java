package com.doc.docquery.mapper;

import com.doc.docquery.entity.DocumentVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 文档版本事实的数据访问边界。 */
@Mapper
public interface DocumentVersionMapper {

    @Insert("""
            INSERT INTO document_version (
                tenant_id, document_id, version_no, status, source_format,
                original_filename, source_bucket, source_object_key,
                source_size_bytes, source_sha256, source_content_type,
                idempotency_key_hash, request_fingerprint, accepted_by_admin_id,
                failure_code, failure_message, ready_at, failed_at,
                content_deleted_at, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{documentId}, #{versionNo}, #{status}, #{sourceFormat},
                #{originalFilename}, #{sourceBucket}, #{sourceObjectKey},
                #{sourceSizeBytes}, #{sourceSha256}, #{sourceContentType},
                #{idempotencyKeyHash}, #{requestFingerprint}, #{acceptedByAdminId},
                #{failureCode}, #{failureMessage}, #{readyAt}, #{failedAt},
                #{contentDeletedAt}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentVersionEntity version);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_id AS documentId,
                   version_no AS versionNo,
                   status,
                   source_format AS sourceFormat,
                   original_filename AS originalFilename,
                   source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey,
                   source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256,
                   source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode,
                   failure_message AS failureMessage,
                   ready_at AS readyAt,
                   failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document_version
            WHERE tenant_id = #{tenantId}
              AND idempotency_key_hash = #{idempotencyKeyHash}
            """)
    /** 按租户范围查找幂等受理结果，原始幂等键不会进入数据库。 */
    DocumentVersionEntity findByTenantAndIdempotencyHash(
            @Param("tenantId") Long tenantId,
            @Param("idempotencyKeyHash") String idempotencyKeyHash
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_id AS documentId,
                   version_no AS versionNo,
                   status,
                   source_format AS sourceFormat,
                   original_filename AS originalFilename,
                   source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey,
                   source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256,
                   source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode,
                   failure_message AS failureMessage,
                   ready_at AS readyAt,
                   failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document_version
            WHERE tenant_id = #{tenantId}
              AND document_id = #{documentId}
              AND id = #{versionId}
            """)
    DocumentVersionEntity findByTenantDocumentAndId(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("versionId") Long versionId
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_id AS documentId,
                   version_no AS versionNo,
                   status,
                   source_format AS sourceFormat,
                   original_filename AS originalFilename,
                   source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey,
                   source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256,
                   source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode,
                   failure_message AS failureMessage,
                   ready_at AS readyAt,
                   failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM document_version
            WHERE id = #{id}
            """)
    DocumentVersionEntity findById(@Param("id") Long id);

    @Select("""
            SELECT id, tenant_id AS tenantId, document_id AS documentId,
                   version_no AS versionNo, status, source_format AS sourceFormat,
                   original_filename AS originalFilename, source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey, source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256, source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   ready_at AS readyAt, failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_version
            WHERE tenant_id = #{tenantId} AND id = #{versionId}
            """)
    DocumentVersionEntity findByTenantAndId(
            @Param("tenantId") Long tenantId,
            @Param("versionId") Long versionId
    );

    @Select({
            "<script>",
            "SELECT id, tenant_id AS tenantId, document_id AS documentId,",
            "version_no AS versionNo, status, source_format AS sourceFormat,",
            "original_filename AS originalFilename, source_bucket AS sourceBucket,",
            "source_object_key AS sourceObjectKey, source_size_bytes AS sourceSizeBytes,",
            "source_sha256 AS sourceSha256, source_content_type AS sourceContentType,",
            "idempotency_key_hash AS idempotencyKeyHash,",
            "request_fingerprint AS requestFingerprint,",
            "accepted_by_admin_id AS acceptedByAdminId,",
            "failure_code AS failureCode, failure_message AS failureMessage,",
            "ready_at AS readyAt, failed_at AS failedAt,",
            "content_deleted_at AS contentDeletedAt,",
            "created_at AS createdAt, updated_at AS updatedAt",
            "FROM document_version",
            "WHERE tenant_id = #{tenantId} AND id IN",
            "<foreach collection='versionIds' item='versionId' open='(' separator=',' close=')'>",
            "#{versionId}",
            "</foreach>",
            "</script>"
    })
    List<DocumentVersionEntity> findByTenantAndIds(
            @Param("tenantId") Long tenantId,
            @Param("versionIds") List<Long> versionIds
    );

    @Select("""
            SELECT id, tenant_id AS tenantId, document_id AS documentId,
                   version_no AS versionNo, status, source_format AS sourceFormat,
                   original_filename AS originalFilename, source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey, source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256, source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   ready_at AS readyAt, failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_version
            WHERE tenant_id = #{tenantId} AND document_id = #{documentId}
            ORDER BY version_no DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DocumentVersionEntity> findManagementPage(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*) FROM document_version
            WHERE tenant_id = #{tenantId} AND document_id = #{documentId}
            """)
    long countByTenantAndDocument(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId
    );

    @Select("""
            SELECT id, tenant_id AS tenantId, document_id AS documentId,
                   version_no AS versionNo, status, source_format AS sourceFormat,
                   original_filename AS originalFilename, source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey, source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256, source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   ready_at AS readyAt, failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_version
            WHERE tenant_id = #{tenantId} AND document_id = #{documentId}
            ORDER BY version_no, id
            """)
    List<DocumentVersionEntity> findByTenantAndDocument(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId
    );

    @Select("""
            SELECT id, tenant_id AS tenantId, document_id AS documentId,
                   version_no AS versionNo, status, source_format AS sourceFormat,
                   original_filename AS originalFilename, source_bucket AS sourceBucket,
                   source_object_key AS sourceObjectKey, source_size_bytes AS sourceSizeBytes,
                   source_sha256 AS sourceSha256, source_content_type AS sourceContentType,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   accepted_by_admin_id AS acceptedByAdminId,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   ready_at AS readyAt, failed_at AS failedAt,
                   content_deleted_at AS contentDeletedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM document_version
            WHERE tenant_id = #{tenantId}
              AND document_id = #{documentId}
              AND id = #{versionId}
            FOR UPDATE
            """)
    /** 锁定候选版本，防止 READY/FAILED 两条终态路径并发提交。 */
    DocumentVersionEntity findByTenantDocumentAndIdForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("versionId") Long versionId
    );

    @Update("""
            UPDATE document_version
            SET status = #{readyStatus},
                failure_code = NULL,
                failure_message = NULL,
                ready_at = #{now},
                failed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{processingStatus}
            """)
    /** 版本只有在完整双索引验收后才能由 PROCESSING 进入 READY。 */
    int markReady(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("processingStatus") String processingStatus,
            @Param("readyStatus") String readyStatus
    );

    @Update("""
            UPDATE document_version
            SET status = #{failedStatus},
                failure_code = #{failureCode},
                failure_message = #{failureMessage},
                failed_at = #{now},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{processingStatus}
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage,
            @Param("now") LocalDateTime now,
            @Param("processingStatus") String processingStatus,
            @Param("failedStatus") String failedStatus
    );

    @Update("""
            UPDATE document_version
            SET status = #{processingStatus},
                failure_code = NULL,
                failure_message = NULL,
                failed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND tenant_id = #{tenantId}
              AND status = #{failedStatus}
            """)
    int resetFailedForRetry(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("now") LocalDateTime now,
            @Param("failedStatus") String failedStatus,
            @Param("processingStatus") String processingStatus
    );

    @Update("""
            UPDATE document_version
            SET content_deleted_at = #{now},
                updated_at = #{now}
            WHERE tenant_id = #{tenantId}
              AND document_id = #{documentId}
              AND content_deleted_at IS NULL
            """)
    int markContentDeletedByDocument(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT COUNT(*)
            FROM document_version
            WHERE source_bucket = #{sourceBucket}
              AND source_object_key = #{sourceObjectKey}
            """)
    /**
     * 检查不可变对象引用是否已被使用；Bucket/Object Key 在全服务范围唯一。
     */
    long countBySourceObject(
            @Param("sourceBucket") String sourceBucket,
            @Param("sourceObjectKey") String sourceObjectKey
    );
}
