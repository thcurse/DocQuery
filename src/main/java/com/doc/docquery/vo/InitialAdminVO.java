package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 新租户的首个管理员响应。
 */
@Getter
@AllArgsConstructor
public class InitialAdminVO {

    private final Long id;
    private final String loginName;
    private final String role;
    private final String status;
    private final Long tenantId;
}
