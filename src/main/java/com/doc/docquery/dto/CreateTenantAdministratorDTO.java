package com.doc.docquery.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 平台管理员新增租户管理员的请求。 */
@Data
@NoArgsConstructor
public class CreateTenantAdministratorDTO {

    private String loginName;
    private String password;
}
