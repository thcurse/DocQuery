package com.doc.docquery.mapper;

import com.doc.docquery.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Outbox 可靠事件数据访问边界；N2.1 只负责原子插入待发布事件。 */
@Mapper
public interface OutboxEventMapper {

    @Insert("""
            INSERT INTO outbox_event (
                tenant_id, document_id, document_version_id, processing_job_id,
                document_deletion_job_id, event_type,
                payload, status, attempt_count, available_at,
                locked_by, locked_until, last_error_code, last_error_message,
                sent_at, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{documentId}, #{documentVersionId}, #{processingJobId},
                #{documentDeletionJobId}, #{eventType},
                CAST(#{payload} AS JSON), #{status}, #{attemptCount}, #{availableAt},
                #{lockedBy}, #{lockedUntil}, #{lastErrorCode}, #{lastErrorMessage},
                #{sentAt}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OutboxEventEntity event);

    @Update("""
            UPDATE outbox_event
            SET status = #{pendingStatus},
                locked_by = NULL,
                locked_until = NULL,
                available_at = #{now},
                updated_at = #{now}
            WHERE status = #{publishingStatus}
              AND locked_until < #{now}
            """)
    int recoverExpiredLeases(
            @Param("pendingStatus") String pendingStatus,
            @Param("publishingStatus") String publishingStatus,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   document_id AS documentId,
                   document_version_id AS documentVersionId,
                   processing_job_id AS processingJobId,
                   document_deletion_job_id AS documentDeletionJobId,
                   event_type AS eventType,
                   CAST(payload AS CHAR) AS payload,
                   status,
                   attempt_count AS attemptCount,
                   available_at AS availableAt,
                   locked_by AS lockedBy,
                   locked_until AS lockedUntil,
                   last_error_code AS lastErrorCode,
                   last_error_message AS lastErrorMessage,
                   sent_at AS sentAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM outbox_event
            WHERE status = #{pendingStatus}
              AND available_at <= #{now}
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEventEntity> findPublishableForUpdate(
            @Param("pendingStatus") String pendingStatus,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE outbox_event
            SET status = #{publishingStatus},
                attempt_count = attempt_count + 1,
                locked_by = #{lockedBy},
                locked_until = #{lockedUntil},
                last_error_code = NULL,
                last_error_message = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{pendingStatus}
            """)
    int claim(
            @Param("id") Long id,
            @Param("pendingStatus") String pendingStatus,
            @Param("publishingStatus") String publishingStatus,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE outbox_event
            SET status = #{sentStatus},
                locked_by = NULL,
                locked_until = NULL,
                sent_at = #{now},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{publishingStatus}
              AND locked_by = #{lockedBy}
            """)
    int markSent(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("publishingStatus") String publishingStatus,
            @Param("sentStatus") String sentStatus,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE outbox_event
            SET status = #{pendingStatus},
                available_at = #{availableAt},
                locked_by = NULL,
                locked_until = NULL,
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = #{publishingStatus}
              AND locked_by = #{lockedBy}
            """)
    int markPublishFailed(
            @Param("id") Long id,
            @Param("lockedBy") String lockedBy,
            @Param("publishingStatus") String publishingStatus,
            @Param("pendingStatus") String pendingStatus,
            @Param("availableAt") LocalDateTime availableAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );
}
