package com.doc.docquery.service;

/** `/answer` 允许映射到外部的有限失败原因。 */
public final class AnswerException extends RuntimeException {

    private final Reason reason;
    private Detail detail = Detail.STRUCTURE;

    public enum Detail { STRUCTURE, CITATIONS, TRUNCATED }

    public static AnswerException invalidOutput(Detail detail) {
        AnswerException failure = new AnswerException(Reason.OUTPUT_INVALID, "Answer output validation failed");
        failure.detail = detail;
        return failure;
    }

    public String publicMessage() {
        return switch (reason) {
            case OUTPUT_INVALID -> switch (detail) {
                case CITATIONS -> "答案引用校验失败，请重试";
                case TRUNCATED -> "模型输出不完整，请重试";
                default -> "模型回答格式校验失败，请重试";
            };
            case MODEL_REQUEST_INVALID -> "模型请求配置不兼容，请检查模型配置";
            case EXECUTION_LIMIT_EXCEEDED -> "检索未能在预算内完成，请缩小问题范围或重试";
            case EXECUTION_TIMEOUT -> "回答超时，请重试";
            case MODEL_UNAVAILABLE -> "模型服务暂时不可用，请重试";
            default -> "回答未能完成，请重试";
        };
    }


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
        MODEL_REQUEST_INVALID,
        OUTPUT_INVALID,
        EXECUTION_LIMIT_EXCEEDED,
        EXECUTION_TIMEOUT
    }
}
