package com.doc.docquery.security;

/** 应用凭证解析失败；原因分类用于稳定区分格式、密钥和主体状态问题。 */
public final class ApplicationCredentialAuthenticationException extends RuntimeException {

    private final Reason reason;

    public ApplicationCredentialAuthenticationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 应用凭证认证失败原因。 */
    public enum Reason {
        INVALID_CREDENTIAL,
        CREDENTIAL_REVOKED,
        APPLICATION_UNAVAILABLE,
        TENANT_UNAVAILABLE
    }
}
