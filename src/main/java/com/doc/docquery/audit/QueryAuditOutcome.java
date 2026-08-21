package com.doc.docquery.audit;

/** 应用查询审计生命周期代码。 */
public enum QueryAuditOutcome {
    STARTED("1"),
    SUCCEEDED("2"),
    FAILED("3"),
    REJECTED("4"),
    INTERRUPTED("5");

    private final String code;

    QueryAuditOutcome(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static QueryAuditOutcome fromCode(String code) {
        for (QueryAuditOutcome value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown query audit outcome");
    }
}
