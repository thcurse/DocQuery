package com.doc.docquery.service;

/** `/answer` 允许映射到外部的有限失败原因。 */
public final class AnswerException extends RuntimeException {

    private final Reason reason;

    public AnswerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AnswerException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        REQUEST_IN_PROGRESS,
        MODEL_UNAVAILABLE,
        OUTPUT_INVALID,
        EXECUTION_LIMIT_EXCEEDED,
        EXECUTION_TIMEOUT
    }
}
