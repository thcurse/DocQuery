package com.doc.docquery.audit;

/** 应用查询审计操作代码。 */
public enum QueryAuditOperation {
    RETRIEVE("1"),
    ANSWER("2");

    private final String code;

    QueryAuditOperation(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static QueryAuditOperation fromCode(String code) {
        for (QueryAuditOperation value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown query audit operation");
    }
}
