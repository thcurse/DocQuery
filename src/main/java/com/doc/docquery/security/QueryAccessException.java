package com.doc.docquery.security;

/**
 * 正式服务 API 的查询前置认证或授权失败。
 *
 * <p>内部 N1 组件保留精确失败原因，本异常只保留允许暴露给服务调用方的两类
 * 结果，避免泄露其他租户的知识库或主体状态。</p>
 */
public final class QueryAccessException extends RuntimeException {

    private final Reason reason;

    public QueryAccessException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 服务面允许稳定映射的认证和资源隐藏分类。 */
    public enum Reason {
        APPLICATION_CREDENTIAL_INVALID,
        KNOWLEDGE_BASE_NOT_AVAILABLE
    }
}
