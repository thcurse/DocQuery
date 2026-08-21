package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 文档列表和详情中的生效/最新版本摘要。 */
@Getter
@AllArgsConstructor
public class DocumentVersionSummaryVO {
    private final Long documentVersionId;
    private final Integer versionNo;
    private final String status;
    private final boolean active;
    private final String failureCode;
    private final String failureMessage;
    private final Boolean failureRetryable;
    private final OffsetDateTime readyAt;
    private final OffsetDateTime failedAt;
}
