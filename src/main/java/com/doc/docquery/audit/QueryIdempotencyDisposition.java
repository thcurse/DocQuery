package com.doc.docquery.audit;

/** 本次HTTP尝试相对于Redis幂等记录的处置。 */
public enum QueryIdempotencyDisposition {
    OWNER("1"),
    REPLAY("2"),
    IN_PROGRESS("3"),
    CONFLICT("4");

    private final String code;

    QueryIdempotencyDisposition(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static QueryIdempotencyDisposition fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (QueryIdempotencyDisposition value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown idempotency disposition");
    }
}
