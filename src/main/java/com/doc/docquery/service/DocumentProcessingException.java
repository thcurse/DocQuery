package com.doc.docquery.service;

/** 完整处理器向 Consumer 声明稳定失败码和是否允许技术重试。 */
public final class DocumentProcessingException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public DocumentProcessingException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public DocumentProcessingException(
            String code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
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
