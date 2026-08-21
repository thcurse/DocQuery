package com.doc.docquery.enums;

import lombok.Getter;

/** 文档异步任务类型；N2.1 仅定义完整入库任务。 */
@Getter
public enum ProcessingJobType {

    INGEST("1");

    private final String code;

    ProcessingJobType(String code) {
        this.code = code;
    }
}
