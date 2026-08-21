package com.doc.docquery.enums;

import lombok.Getter;

/** 文档受理事务产生的可靠事件类型。 */
@Getter
public enum OutboxEventType {

    DOCUMENT_VERSION_PROCESS_REQUESTED("1"),
    DOCUMENT_DELETE_REQUESTED("2");

    private final String code;

    OutboxEventType(String code) {
        this.code = code;
    }
}
