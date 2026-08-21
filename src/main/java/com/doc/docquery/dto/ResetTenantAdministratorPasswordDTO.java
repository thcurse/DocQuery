package com.doc.docquery.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 平台管理员为租户管理员设置新密码的请求。 */
@Data
@NoArgsConstructor
public class ResetTenantAdministratorPasswordDTO {

    private String password;
}
