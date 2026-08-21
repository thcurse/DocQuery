package com.doc.docquery.cache;

/** 查询幂等作用域中的正式服务操作类型。 */
public enum QueryOperation {
    RETRIEVE("retrieve"),
    ANSWER("answer");

    private final String keyPart;

    QueryOperation(String keyPart) {
        this.keyPart = keyPart;
    }

    public String keyPart() {
        return keyPart;
    }
}
