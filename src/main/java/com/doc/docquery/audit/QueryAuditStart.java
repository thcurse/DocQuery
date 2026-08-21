package com.doc.docquery.audit;

import com.doc.docquery.security.ApplicationCredentialPrincipal;

/** 已获得可信应用主体后创建审计STARTED所需的最小元数据。 */
public record QueryAuditStart(
        String requestId,
        ApplicationCredentialPrincipal principal,
        String credentialFingerprint,
        long knowledgeBaseId,
        QueryAuditOperation operation,
        String callerTraceId,
        String actorRef,
        String querySha256,
        Integer queryCodePoints,
        String requestedMode
) {
}
