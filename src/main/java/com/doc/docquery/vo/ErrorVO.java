package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 稳定错误响应。
 */
@Getter
@AllArgsConstructor
public class ErrorVO {

    private final String code;
    private final String message;
}
