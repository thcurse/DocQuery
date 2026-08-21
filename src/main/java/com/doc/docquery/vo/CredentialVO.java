package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 应用凭证摘要响应。
 */
@Getter
@AllArgsConstructor
public class CredentialVO {

    private final Long id;
    private final Long applicationId;
    private final String name;
    private final String keyIdPrefix;
    private final String status;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime lastUsedAt;
    private final OffsetDateTime revokedAt;
}
