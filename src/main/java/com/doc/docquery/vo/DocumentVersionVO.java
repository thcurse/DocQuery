package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 管理面版本历史视图，不暴露对象存储位置和内容摘要。 */
@Getter
@AllArgsConstructor
public class DocumentVersionVO {
    private final Long documentVersionId;
    private final Integer versionNo;
    private final String status;
    private final boolean active;
    private final String sourceFormat;
    private final String originalFilename;
    private final Long sourceSizeBytes;
    private final String failureCode;
    private final String failureMessage;
    private final Boolean failureRetryable;
    private final OffsetDateTime readyAt;
    private final OffsetDateTime failedAt;
    private final OffsetDateTime contentDeletedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final ProcessingJobAttemptVO latestProcessingJob;
}
