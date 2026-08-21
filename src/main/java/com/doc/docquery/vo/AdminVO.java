package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 当前管理员信息。
 */
@Getter
@AllArgsConstructor
public class AdminVO {

    private final Long id;
    private final String loginName;
    private final String role;
    private final Long tenantId;
}
