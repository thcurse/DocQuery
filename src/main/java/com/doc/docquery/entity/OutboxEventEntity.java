package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 与文档受理事务一同落库的可靠消息事件。
 *
 * <p>N2.1 只保存待发布事实；N2.2 的 Publisher 才会使用租约、重试和发送
 * 字段。Payload 只包含稳定业务 ID，不包含对象键、摘要或凭证。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键。 */
    private Long tenantId;
    /** 事件关联的逻辑文档 ID。 */
    private Long documentId;
    /** 事件关联的文档版本 ID。 */
    private Long documentVersionId;
    /** 事件关联的处理任务 ID。 */
    private Long processingJobId;
    /** 删除事件关联的删除任务 ID。 */
    private Long documentDeletionJobId;
    /** 稳定事件类型码。 */
    private String eventType;
    /** 仅含稳定业务 ID 的 JSON Payload。 */
    private String payload;
    /** PENDING、PUBLISHING 或 SENT 状态码。 */
    private String status;
    /** 发布尝试次数。 */
    private Integer attemptCount;
    /** UTC 下次允许发布时间。 */
    private LocalDateTime availableAt;
    /** 当前租约持有者。 */
    private String lockedBy;
    /** UTC 租约截止时间。 */
    private LocalDateTime lockedUntil;
    /** 最近发布失败错误码。 */
    private String lastErrorCode;
    /** 最近发布失败说明。 */
    private String lastErrorMessage;
    /** UTC 成功发送时间。 */
    private LocalDateTime sentAt;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;
}
