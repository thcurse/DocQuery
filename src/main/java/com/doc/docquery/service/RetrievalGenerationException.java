package com.doc.docquery.service;

/** N2.4 对模型、输入、对象存储和数据库失败的稳定分类。 */
public class RetrievalGenerationException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public RetrievalGenerationException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public RetrievalGenerationException(
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
