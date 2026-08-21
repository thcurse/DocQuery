package com.doc.docquery.service;

/** 对象存储端口的稳定失败分类，不向 HTTP 层泄露 SDK 异常或凭证。 */
public final class ObjectStorageException extends RuntimeException {

    private final Reason reason;

    public ObjectStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ObjectStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 只保留业务协调层需要区分的两类失败。 */
    public enum Reason {
        UNAVAILABLE,
        FILE_TOO_LARGE
    }
}
