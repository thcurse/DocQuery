package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 不含密码材料的租户管理员管理视图。 */
@Getter
@AllArgsConstructor
public class TenantAdministratorVO {

    private final Long id;
    private final Long tenantId;
    private final String loginName;
    private final String role;
    private final String status;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
