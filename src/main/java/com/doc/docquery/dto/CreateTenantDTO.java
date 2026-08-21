package com.doc.docquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建租户请求。
 */
@Data
public class CreateTenantDTO {

    /** 租户名称。 */
    @NotBlank(message = "租户名称不能为空")
    @Size(max = 200, message = "租户名称不能超过200个字符")
    private String tenantName;

    /** 首个租户管理员登录名。 */
    @NotBlank(message = "管理员登录名不能为空")
    @Size(max = 64, message = "管理员登录名不能超过64个字符")
    private String adminLoginName;

    /** 首个租户管理员密码。 */
    @NotBlank(message = "管理员密码不能为空")
    @Size(min = 12, message = "管理员密码不能少于12个字符")
    private String adminPassword;
}
