package com.doc.docquery.service;

/** `/retrieve` 允许稳定映射到外部的有限失败原因。 */
public final class RetrieveException extends RuntimeException {

    private final Reason reason;

    public RetrieveException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RetrieveException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        REQUEST_IN_PROGRESS,
        QUERY_EMBEDDING_UNAVAILABLE,
        SEARCH_UNAVAILABLE,
        EVIDENCE_UNAVAILABLE
    }
}
