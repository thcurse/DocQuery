package com.doc.docquery.enums;

import lombok.Getter;

/** 单次文档删除清理尝试的执行状态。 */
@Getter
public enum DocumentDeletionJobStatus {

    PENDING("1"),
    RUNNING("2"),
    SUCCEEDED("3"),
    FAILED("4");

    private final String code;

    DocumentDeletionJobStatus(String code) {
        this.code = code;
    }
}
