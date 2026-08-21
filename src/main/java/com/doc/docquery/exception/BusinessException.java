package com.doc.docquery.exception;

/**
 * 可安全映射为管理面 HTTP 响应的业务异常。
 *
 * <p>{@link Failure} 决定 HTTP 状态，稳定的 {@code code} 供调用方判断具体
 * 失败原因，message 只提供可读说明。</p>
 */
public final class BusinessException extends RuntimeException {

    private final Failure failure;
    private final String code;

    public BusinessException(Failure failure, String code, String message) {
        super(message);
        this.failure = failure;
        this.code = code;
    }

    public Failure failure() {
        return failure;
    }

    public String code() {
        return code;
    }

    /** 业务失败到 HTTP 状态的有限分类。 */
    public enum Failure {
        VALIDATION,
        NOT_FOUND,
        CONFLICT,
        FORBIDDEN,
        TOO_LARGE,
        UNSUPPORTED_MEDIA_TYPE,
        UNAVAILABLE
    }
}
