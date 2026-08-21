package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 管理面处理任务视图。 */
@Getter
@AllArgsConstructor
public class ProcessingJobVO {
    private final Long processingJobId;
    private final Long knowledgeBaseId;
    private final String knowledgeBaseName;
    private final Long documentId;
    private final String documentName;
    private final Long documentVersionId;
    private final Integer versionNo;
    private final String jobType;
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
