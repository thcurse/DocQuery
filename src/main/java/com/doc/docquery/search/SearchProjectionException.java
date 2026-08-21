package com.doc.docquery.search;

/** Elasticsearch 投影阶段向完整入库链路暴露的稳定错误分类。 */
public final class SearchProjectionException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public SearchProjectionException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public SearchProjectionException(
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
