package com.doc.docquery.enums;

import lombok.Getter;

/** 单次文档处理任务尝试的执行状态。 */
@Getter
public enum ProcessingJobStatus {

    PENDING("1"),
    RUNNING("2"),
    SUCCEEDED("3"),
    FAILED("4");

    private final String code;

    ProcessingJobStatus(String code) {
        this.code = code;
    }
}
