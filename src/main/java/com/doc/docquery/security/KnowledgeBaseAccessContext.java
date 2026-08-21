package com.doc.docquery.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通过知识库授权判断后的可信访问上下文。
 */
@Getter
@AllArgsConstructor
public class KnowledgeBaseAccessContext {

    private final Long credentialId;
    private final Long applicationId;
    private final Long tenantId;
    private final Long knowledgeBaseId;
    private final String grantedPermission;
}
