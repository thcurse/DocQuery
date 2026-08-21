package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 管理员人工重试的 202 受理结果。 */
@Getter
@AllArgsConstructor
public class ProcessingJobRetryAcceptedVO {
    private final Long sourceProcessingJobId;
    private final Long documentId;
    private final Long documentVersionId;
    private final Long processingJobId;
    private final Integer attemptNo;
    private final String versionStatus;
    private final String jobStatus;
    private final boolean replayed;
    private final OffsetDateTime acceptedAt;
}
