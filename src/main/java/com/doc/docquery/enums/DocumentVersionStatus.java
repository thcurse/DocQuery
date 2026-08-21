package com.doc.docquery.enums;

import lombok.Getter;

/** 文档版本从受理到可检索或失败的处理状态。 */
@Getter
public enum DocumentVersionStatus {

    PROCESSING("1"),
    READY("2"),
    FAILED("3");

    private final String code;

    DocumentVersionStatus(String code) {
        this.code = code;
    }
}
