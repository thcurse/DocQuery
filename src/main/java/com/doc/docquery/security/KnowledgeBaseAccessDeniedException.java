package com.doc.docquery.security;

/** 应用调用知识库时的授权失败，避免与管理面的业务异常耦合。 */
public final class KnowledgeBaseAccessDeniedException extends RuntimeException {

    private final Reason reason;

    public KnowledgeBaseAccessDeniedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 知识库访问拒绝原因。 */
    public enum Reason {
        INVALID_CONTEXT,
        CREDENTIAL_UNAVAILABLE,
        APPLICATION_UNAVAILABLE,
        TENANT_UNAVAILABLE,
        KNOWLEDGE_BASE_UNAVAILABLE,
        TENANT_MISMATCH,
        GRANT_MISSING,
        GRANT_REVOKED,
        INSUFFICIENT_PERMISSION
    }
}
