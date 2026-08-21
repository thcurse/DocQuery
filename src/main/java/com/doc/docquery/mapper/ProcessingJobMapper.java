package com.doc.docquery.mapper;

import com.doc.docquery.dto.ProcessingJobManagementPageQueryDTO;
import com.doc.docquery.dto.ProcessingJobManagementRowDTO;
import com.doc.docquery.entity.ProcessingJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 文档处理任务尝试的数据访问边界。 */
@Mapper
public interface ProcessingJobMapper {

    @Insert("""
            INSERT INTO processing_job (
                tenant_id, document_version_id, job_type, attempt_no, status,
                failure_code, failure_message, failure_retryable,
                idempotency_key_hash, request_fingerprint, started_at, finished_at,
                created_at, updated_at
            ) VALUES (
                #{tenantId}, #{documentVersionId}, #{jobType}, #{attemptNo}, #{status},
                #{failureCode}, #{failureMessage}, #{failureRetryable},
                #{idempotencyKeyHash}, #{requestFingerprint}, #{startedAt}, #{finishedAt},
                #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProcessingJobEntity job);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType,
                   attempt_no AS attemptNo,
                   status,
                   lease_owner AS leaseOwner,
                   lease_until AS leaseUntil,
                   failure_code AS failureCode,
                   failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt,
                   finished_at AS finishedAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM processing_job
            WHERE tenant_id = #{tenantId}
              AND document_version_id = #{documentVersionId}
              AND attempt_no = 1
            """)
    /** 读取版本受理时原子创建的首次处理任务，用于幂等响应重建。 */
    ProcessingJobEntity findInitialByTenantAndVersion(
            @Param("tenantId") Long tenantId,
            @Param("documentVersionId") Long documentVersionId
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType,
                   attempt_no AS attemptNo,
                   status,
                   lease_owner AS leaseOwner,
                   lease_until AS leaseUntil,
                   failure_code AS failureCode,
                   failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt,
                   finished_at AS finishedAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM processing_job
            WHERE id = #{id}
            """)
    ProcessingJobEntity findById(@Param("id") Long id);

    @Select("""
            SELECT id, tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType, attempt_no AS attemptNo, status,
                   lease_owner AS leaseOwner, lease_until AS leaseUntil,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt, finished_at AS finishedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM processing_job
            WHERE tenant_id = #{tenantId} AND id = #{jobId}
            """)
    ProcessingJobEntity findByTenantAndId(
            @Param("tenantId") Long tenantId,
            @Param("jobId") Long jobId
    );

    @Select("""
            SELECT id, tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType, attempt_no AS attemptNo, status,
                   lease_owner AS leaseOwner, lease_until AS leaseUntil,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt, finished_at AS finishedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM processing_job
            WHERE tenant_id = #{tenantId}
              AND idempotency_key_hash = #{idempotencyKeyHash}
            """)
    ProcessingJobEntity findByTenantAndIdempotencyHash(
            @Param("tenantId") Long tenantId,
            @Param("idempotencyKeyHash") String idempotencyKeyHash
    );

    @Select("""
            SELECT id, tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType, attempt_no AS attemptNo, status,
                   lease_owner AS leaseOwner, lease_until AS leaseUntil,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt, finished_at AS finishedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM processing_job
            WHERE id = #{id}
            FOR UPDATE
            """)
    /** 锁定处理尝试，最终提交必须仍持有同一租约。 */
    ProcessingJobEntity findByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT id, tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType, attempt_no AS attemptNo, status,
                   lease_owner AS leaseOwner, lease_until AS leaseUntil,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt, finished_at AS finishedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM processing_job
            WHERE tenant_id = #{tenantId}
              AND document_version_id = #{documentVersionId}
            ORDER BY attempt_no DESC, id DESC
            LIMIT 1
            FOR UPDATE
            """)
    ProcessingJobEntity findLatestByTenantAndVersionForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("documentVersionId") Long documentVersionId
    );

    @Select("""
            SELECT id, tenant_id AS tenantId,
                   document_version_id AS documentVersionId,
                   job_type AS jobType, attempt_no AS attemptNo, status,
                   lease_owner AS leaseOwner, lease_until AS leaseUntil,
                   failure_code AS failureCode, failure_message AS failureMessage,
                   failure_retryable AS failureRetryable,
                   idempotency_key_hash AS idempotencyKeyHash,
                   request_fingerprint AS requestFingerprint,
                   started_at AS startedAt, finished_at AS finishedAt,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM processing_job
            WHERE tenant_id = #{tenantId}
              AND document_version_id = #{documentVersionId}
            ORDER BY attempt_no DESC, id DESC
            """)
    List<ProcessingJobEntity> findAttemptsByTenantAndVersion(
            @Param("tenantId") Long tenantId,
            @Param("documentVersionId") Long documentVersionId
    );

    @Select({
            "<script>",
            "SELECT p.id, p.tenant_id AS tenantId,",
            "p.document_version_id AS documentVersionId,",
            "p.job_type AS jobType, p.attempt_no AS attemptNo, p.status,",
            "p.lease_owner AS leaseOwner, p.lease_until AS leaseUntil,",
            "p.failure_code AS failureCode, p.failure_message AS failureMessage,",
            "p.failure_retryable AS failureRetryable,",
            "p.idempotency_key_hash AS idempotencyKeyHash,",
            "p.request_fingerprint AS requestFingerprint,",
            "p.started_at AS startedAt, p.finished_at AS finishedAt,",
            "p.created_at AS createdAt, p.updated_at AS updatedAt",
            "FROM processing_job p",
            "INNER JOIN (",
            "SELECT document_version_id, MAX(attempt_no) AS attempt_no",
            "FROM processing_job WHERE tenant_id = #{tenantId}",
            "AND document_version_id IN",
            "<foreach collection='versionIds' item='versionId' open='(' separator=',' close=')'>",
            "#{versionId}",
            "</foreach>",
            "GROUP BY document_version_id",
            ") latest ON latest.document_version_id = p.document_version_id",
            "AND latest.attempt_no = p.attempt_no",
            "WHERE p.tenant_id = #{tenantId}",
            "</script>"
    })
    List<ProcessingJobEntity> findLatestByTenantAndVersions(
            @Param("tenantId") Long tenantId,
            @Param("versionIds") List<Long> versionIds
    );

    @Select("""
            SELECT COUNT(*) FROM processing_job
            WHERE tenant_id = #{tenantId}
              AND document_version_id = #{documentVersionId}
              AND status IN (#{pendingStatus}, #{runningStatus})
            """)
    long countInProgressByTenantAndVersion(
            @Param("tenantId") Long tenantId,
            @Param("documentVersionId") Long documentVersionId,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @Select("""
            SELECT COUNT(*)
            FROM processing_job p
            INNER JOIN document_version v
                    ON v.id = p.document_version_id
                   AND v.tenant_id = p.tenant_id
            WHERE p.tenant_id = #{tenantId}
              AND v.document_id = #{documentId}
              AND p.status IN (#{pendingStatus}, #{runningStatus})
            """)
    long countInProgressByTenantAndDocument(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @SelectProvider(type = ProcessingJobManagementSqlProvider.class, method = "findPage")
    List<ProcessingJobManagementRowDTO> findManagementPage(
            ProcessingJobManagementPageQueryDTO query
    );

    @SelectProvider(type = ProcessingJobManagementSqlProvider.class, method = "count")
    long countManagementPage(ProcessingJobManagementPageQueryDTO query);

    @Select("SELECT " + ProcessingJobManagementSqlProvider.COLUMNS
            + ProcessingJobManagementSqlProvider.JOINS + """
             WHERE p.tenant_id = #{tenantId} AND p.id = #{jobId}
            """)
    ProcessingJobManagementRowDTO findManagementByTenantAndId(
            @Param("tenantId") Long tenantId,
            @Param("jobId") Long jobId
    );

    @Update("""
            UPDATE processing_job
            SET status = #{runningStatus},
                lease_owner = #{leaseOwner},
                lease_until = #{leaseUntil},
                started_at = COALESCE(started_at, #{now}),
                updated_at = #{now}
            WHERE id = #{id}
              AND tenant_id = #{tenantId}
              AND document_version_id = #{documentVersionId}
              AND (
                    status = #{pendingStatus}
                    OR (status = #{runningStatus} AND lease_until < #{now})
                  )
            """)
    int acquireLease(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("documentVersionId") Long documentVersionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @Update("""
            UPDATE processing_job
            SET lease_until = #{leaseUntil},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{runningStatus}
              AND lease_owner = #{leaseOwner}
            """)
    int renewLease(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now,
            @Param("runningStatus") String runningStatus
    );

    @Update("""
            UPDATE processing_job
            SET status = #{pendingStatus},
                lease_owner = NULL,
                lease_until = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{runningStatus}
              AND lease_owner = #{leaseOwner}
            """)
    int releaseForRetry(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") LocalDateTime now,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @Update("""
            UPDATE processing_job
            SET status = #{failedStatus},
                lease_owner = NULL,
                lease_until = NULL,
                failure_code = #{failureCode},
                failure_message = #{failureMessage},
                failure_retryable = #{failureRetryable},
                finished_at = #{now},
                updated_at = #{now}
            WHERE id = #{id}
              AND status IN (#{pendingStatus}, #{runningStatus})
              AND (lease_owner = #{leaseOwner} OR lease_owner IS NULL)
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage,
            @Param("failureRetryable") boolean failureRetryable,
            @Param("now") LocalDateTime now,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus,
            @Param("failedStatus") String failedStatus
    );

    @Update("""
            UPDATE processing_job
            SET status = #{succeededStatus},
                lease_owner = NULL,
                lease_until = NULL,
                failure_code = NULL,
                failure_message = NULL,
                failure_retryable = NULL,
                finished_at = #{now},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{runningStatus}
              AND lease_owner = #{leaseOwner}
            """)
    /** 只有当前租约持有者可以提交 SUCCEEDED，防止过期 Consumer 覆盖新尝试。 */
    int markSucceeded(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") LocalDateTime now,
            @Param("runningStatus") String runningStatus,
            @Param("succeededStatus") String succeededStatus
    );
}
