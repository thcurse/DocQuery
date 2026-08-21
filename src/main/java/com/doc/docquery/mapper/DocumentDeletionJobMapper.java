package com.doc.docquery.mapper;

import com.doc.docquery.entity.DocumentDeletionJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 文档级异步删除尝试的数据访问边界。 */
@Mapper
public interface DocumentDeletionJobMapper {

    String COLUMNS = """
            id, tenant_id AS tenantId, knowledge_base_id AS knowledgeBaseId,
            document_id AS documentId, attempt_no AS attemptNo, status,
            lease_owner AS leaseOwner, lease_until AS leaseUntil,
            failure_code AS failureCode, failure_message AS failureMessage,
            failure_retryable AS failureRetryable,
            idempotency_key_hash AS idempotencyKeyHash,
            request_fingerprint AS requestFingerprint,
            requested_by_admin_id AS requestedByAdminId,
            started_at AS startedAt, finished_at AS finishedAt,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO document_deletion_job (
                tenant_id, knowledge_base_id, document_id, attempt_no, status,
                lease_owner, lease_until, failure_code, failure_message,
                failure_retryable, idempotency_key_hash, request_fingerprint,
                requested_by_admin_id, started_at, finished_at, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{knowledgeBaseId}, #{documentId}, #{attemptNo}, #{status},
                #{leaseOwner}, #{leaseUntil}, #{failureCode}, #{failureMessage},
                #{failureRetryable}, #{idempotencyKeyHash}, #{requestFingerprint},
                #{requestedByAdminId}, #{startedAt}, #{finishedAt}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentDeletionJobEntity job);

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job WHERE id = #{id}")
    DocumentDeletionJobEntity findById(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job "
            + "WHERE tenant_id = #{tenantId} AND idempotency_key_hash = #{idempotencyKeyHash}")
    DocumentDeletionJobEntity findByTenantAndIdempotencyHash(
            @Param("tenantId") Long tenantId,
            @Param("idempotencyKeyHash") String idempotencyKeyHash
    );

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job "
            + "WHERE tenant_id = #{tenantId} AND document_id = #{documentId} "
            + "ORDER BY attempt_no DESC, id DESC LIMIT 1")
    DocumentDeletionJobEntity findLatestByTenantAndDocument(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId
    );

    @Select("SELECT " + COLUMNS + " FROM document_deletion_job "
            + "WHERE tenant_id = #{tenantId} AND document_id = #{documentId} "
            + "ORDER BY attempt_no DESC, id DESC LIMIT 1 FOR UPDATE")
    DocumentDeletionJobEntity findLatestByTenantAndDocumentForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId
    );

    @Select("SELECT " + COLUMNS
            + " FROM document_deletion_job WHERE id = #{id} FOR UPDATE")
    DocumentDeletionJobEntity findByIdForUpdate(@Param("id") Long id);

    @Select({
            "<script>",
            "SELECT j.id, j.tenant_id AS tenantId, j.knowledge_base_id AS knowledgeBaseId,",
            "j.document_id AS documentId, j.attempt_no AS attemptNo, j.status,",
            "j.lease_owner AS leaseOwner, j.lease_until AS leaseUntil,",
            "j.failure_code AS failureCode, j.failure_message AS failureMessage,",
            "j.failure_retryable AS failureRetryable,",
            "j.idempotency_key_hash AS idempotencyKeyHash,",
            "j.request_fingerprint AS requestFingerprint,",
            "j.requested_by_admin_id AS requestedByAdminId,",
            "j.started_at AS startedAt, j.finished_at AS finishedAt,",
            "j.created_at AS createdAt, j.updated_at AS updatedAt",
            "FROM document_deletion_job j",
            "INNER JOIN (",
            "SELECT document_id, MAX(attempt_no) AS attempt_no",
            "FROM document_deletion_job WHERE tenant_id = #{tenantId}",
            "AND document_id IN",
            "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>",
            "#{documentId}",
            "</foreach>",
            "GROUP BY document_id",
            ") latest ON latest.document_id = j.document_id",
            "AND latest.attempt_no = j.attempt_no",
            "WHERE j.tenant_id = #{tenantId}",
            "</script>"
    })
    List<DocumentDeletionJobEntity> findLatestByTenantAndDocuments(
            @Param("tenantId") Long tenantId,
            @Param("documentIds") List<Long> documentIds
    );

    @Select("""
            SELECT COUNT(*) FROM document_deletion_job
            WHERE tenant_id = #{tenantId}
              AND document_id = #{documentId}
              AND status IN (#{pendingStatus}, #{runningStatus})
            """)
    long countInProgress(
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @Update("""
            UPDATE document_deletion_job
            SET status = #{runningStatus},
                lease_owner = #{leaseOwner},
                lease_until = #{leaseUntil},
                started_at = COALESCE(started_at, #{now}),
                updated_at = #{now}
            WHERE id = #{id}
              AND tenant_id = #{tenantId}
              AND document_id = #{documentId}
              AND (
                    status = #{pendingStatus}
                    OR (status = #{runningStatus} AND lease_until < #{now})
                  )
            """)
    int acquireLease(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("documentId") Long documentId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("now") LocalDateTime now,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @Update("""
            UPDATE document_deletion_job
            SET lease_until = #{leaseUntil}, updated_at = #{now}
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
            UPDATE document_deletion_job
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
            UPDATE document_deletion_job
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
            UPDATE document_deletion_job
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
    int markSucceeded(
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") LocalDateTime now,
            @Param("runningStatus") String runningStatus,
            @Param("succeededStatus") String succeededStatus
    );
}
