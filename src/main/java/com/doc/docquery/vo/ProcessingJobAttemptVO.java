package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 单个文档版本的一次处理尝试；不暴露租约和幂等内部字段。 */
@Getter
@AllArgsConstructor
public class ProcessingJobAttemptVO {
    private final Long processingJobId;
    private final Integer attemptNo;
    private final String status;
    private final String failureCode;
    private final String failureMessage;
    private final Boolean failureRetryable;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime finishedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
