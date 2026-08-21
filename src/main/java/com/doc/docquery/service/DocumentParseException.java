package com.doc.docquery.service;

/** N2.3 Parser 和标准化服务暴露的稳定失败码及技术重试属性。 */
public final class DocumentParseException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public DocumentParseException(
            String code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public DocumentParseException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
