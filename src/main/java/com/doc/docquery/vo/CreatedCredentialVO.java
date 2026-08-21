package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 应用凭证创建响应；完整凭证只在本响应中出现一次。
 */
@Getter
@AllArgsConstructor
public class CreatedCredentialVO {

    private final Long id;
    private final Long applicationId;
    private final String name;
    private final String keyIdPrefix;
    private final String credential;
    private final String status;
    private final OffsetDateTime createdAt;
}
