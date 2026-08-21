package com.doc.docquery.enums;

import lombok.Getter;

/** N1 管理对象共用的生命周期状态码。 */
@Getter
public enum StatusCode {

    ACTIVE("1"),
    DISABLED("2"),
    REVOKED("3");

    private final String code;

    StatusCode(String code) {
        this.code = code;
    }
}
