package com.doc.docquery.stream;

/** Safe public stream failures; never contains provider payloads. */
public final class AnswerStreamException extends RuntimeException {
    private final String code;
    private final boolean clearPreview;
    public AnswerStreamException(String code, String message, boolean clearPreview) {
        super(message);
        this.code = code;
        this.clearPreview = clearPreview;
    }
    public String code() { return code; }
    public boolean clearPreview() { return clearPreview; }
    public static AnswerStreamException cancelled() {
        return new AnswerStreamException("ANSWER_INTERRUPTED", "回答已中断，部分内容未经最终校验", false);
    }
}
