package com.doc.docquery.service;

import com.doc.docquery.enums.GrantPermission;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.security.KnowledgeBaseAccessContext;

/** 应用身份访问知识库时的统一 Tenant、主体状态和 Grant 授权边界。 */
public interface KnowledgeBaseAccessAuthorizer {

    /** 授权成功后返回只含可信 ID 和权限的访问上下文。 */
    KnowledgeBaseAccessContext authorize(
            ApplicationCredentialPrincipal principal,
            long knowledgeBaseId,
            GrantPermission requiredPermission
    );
}
