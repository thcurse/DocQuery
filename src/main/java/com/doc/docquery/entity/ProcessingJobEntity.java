package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文档版本处理任务的一次尝试记录。
 *
 * <p>同一版本可在后续阶段拥有多个 attempt；N2.1 只创建 attemptNo=1 的
 * PENDING 入库任务。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingJobEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 待处理文档版本 ID。 */
    private Long documentVersionId;
    /** 任务类型码；当前仅 INGEST。 */
    private String jobType;
    /** 从 1 开始的尝试序号。 */
    private Integer attemptNo;
    /** PENDING、RUNNING、SUCCEEDED 或 FAILED 状态码。 */
    private String status;
    /** 当前 Consumer 租约持有者。 */
    private String leaseOwner;
    /** UTC 租约截止时间。 */
    private LocalDateTime leaseUntil;
    /** 本次尝试的失败错误码。 */
    private String failureCode;
    /** 本次尝试的失败说明。 */
    private String failureMessage;
    /** 最终失败是否允许管理员创建新的人工尝试；非失败状态为空。 */
    private Boolean failureRetryable;
    /** 人工重试幂等键的 SHA-256；初始任务为空。 */
    private String idempotencyKeyHash;
    /** 人工重试命令的稳定指纹；初始任务为空。 */
    private String requestFingerprint;
    /** UTC 开始执行时间。 */
    private LocalDateTime startedAt;
    /** UTC 执行结束时间。 */
    private LocalDateTime finishedAt;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;
}
