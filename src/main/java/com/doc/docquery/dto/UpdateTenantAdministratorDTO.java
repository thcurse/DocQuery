package com.doc.docquery.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 平台管理员启停租户管理员的请求。 */
@Data
@NoArgsConstructor
public class UpdateTenantAdministratorDTO {

    private String status;
}
