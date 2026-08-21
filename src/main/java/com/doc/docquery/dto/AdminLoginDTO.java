package com.doc.docquery.dto;

import lombok.Data;

/**
 * 管理员登录请求。
 */
@Data
public class AdminLoginDTO {

    /** 管理员登录名。 */
    private String loginName;

    /** 管理员密码。 */
    private String password;
}
