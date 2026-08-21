package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 文档上传数据库受理结果。
 */
@Getter
@AllArgsConstructor
public class DocumentUploadAcceptedVO {

    private final Long documentId;
    private final Long documentVersionId;
    private final Integer versionNo;
    private final Long processingJobId;
    private final String documentStatus;
    private final String versionStatus;
    private final String jobStatus;
    private final OffsetDateTime acceptedAt;
}
