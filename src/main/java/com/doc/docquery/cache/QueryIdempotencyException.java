package com.doc.docquery.cache;

/** 查询请求幂等的稳定失败分类，不包含原始 Key、响应或凭证。 */
public final class QueryIdempotencyException extends RuntimeException {

    private final Reason reason;

    public QueryIdempotencyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public QueryIdempotencyException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 后续服务 API 映射 Validation、409 和 503 时使用的有限原因。 */
    public enum Reason {
        INVALID_REQUEST,
        IDEMPOTENCY_CONFLICT,
        CONTEXT_CHANGED,
        UNAVAILABLE,
        OWNERSHIP_LOST,
        RESULT_TOO_LARGE,
        CORRUPTED_STATE
    }
}
