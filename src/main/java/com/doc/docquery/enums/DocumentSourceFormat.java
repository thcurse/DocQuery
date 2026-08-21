package com.doc.docquery.enums;

import lombok.Getter;

import java.util.Arrays;

/** 首批允许受理的原文件格式。 */
@Getter
public enum DocumentSourceFormat {

    PDF("1"),
    DOCX("2"),
    TXT("3"),
    MARKDOWN("4");

    private final String code;

    DocumentSourceFormat(String code) {
        this.code = code;
    }

    /** 从数据库/API 状态码解析格式，未知格式直接拒绝。 */
    public static DocumentSourceFormat fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported document source format code"
                ));
    }
}
