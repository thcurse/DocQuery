package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 创建租户响应。
 */
@Getter
@AllArgsConstructor
public class CreatedTenantVO {

    private final TenantVO tenant;
    private final InitialAdminVO initialAdmin;
}
