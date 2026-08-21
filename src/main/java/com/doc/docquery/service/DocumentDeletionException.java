package com.doc.docquery.service;

/** 删除处理器的稳定失败分类；敏感下游异常只作为 cause 保留在进程内。 */
public final class DocumentDeletionException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public DocumentDeletionException(
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
