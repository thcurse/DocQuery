package com.doc.docquery.enums;

import lombok.Getter;

/** 逻辑文档生命周期；与具体版本处理状态相互独立。 */
@Getter
public enum DocumentStatus {

    ACTIVE("1"),
    DELETING("2"),
    DELETED("3");

    private final String code;

    DocumentStatus(String code) {
        this.code = code;
    }
}
