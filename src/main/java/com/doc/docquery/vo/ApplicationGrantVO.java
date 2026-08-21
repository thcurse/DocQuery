package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 应用知识库授权响应。
 */
@Getter
@AllArgsConstructor
public class ApplicationGrantVO {

    private final Long id;
    private final Long tenantId;
    private final Long applicationId;
    private final Long knowledgeBaseId;
    private final String permission;
    private final String status;
    private final OffsetDateTime grantedAt;
    private final Long grantedBy;
    private final OffsetDateTime revokedAt;
    private final Long revokedBy;
}
