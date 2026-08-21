package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 删除或人工重试命令的异步受理结果。 */
@Getter
@AllArgsConstructor
public class DocumentDeletionAcceptedVO {
    private final Long documentId;
    private final Long deletionJobId;
    private final Integer attemptNo;
    private final String documentStatus;
    private final String jobStatus;
    private final boolean replayed;
    private final OffsetDateTime acceptedAt;
}
