package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 管理面逻辑文档视图，始终分别表达 activeVersion 与 latestVersion。 */
@Getter
@AllArgsConstructor
public class DocumentManagementVO {
    private final Long documentId;
    private final Long knowledgeBaseId;
    private final String name;
    private final String documentStatus;
    private final DocumentVersionSummaryVO activeVersion;
    private final DocumentVersionSummaryVO latestVersion;
    private final OffsetDateTime deletionRequestedAt;
    private final OffsetDateTime deletedAt;
    private final DocumentDeletionJobVO latestDeletionJob;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
