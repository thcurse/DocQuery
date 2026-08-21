package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 管理面可见的最近一次文档删除尝试，不暴露租约和幂等摘要。 */
@Getter
@AllArgsConstructor
public class DocumentDeletionJobVO {
    private final Long deletionJobId;
    private final Integer attemptNo;
    private final String status;
    private final String failureCode;
    private final String failureMessage;
    private final Boolean failureRetryable;
    private final Long requestedByAdminId;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime finishedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
