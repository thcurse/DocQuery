package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * CSRF令牌响应。
 */
@Getter
@AllArgsConstructor
public class CsrfTokenVO {

    private final String headerName;
    private final String parameterName;
    private final String token;
}
