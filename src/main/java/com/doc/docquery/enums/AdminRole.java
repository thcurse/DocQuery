package com.doc.docquery.enums;

import lombok.Getter;

import java.util.Arrays;

/** 管理员角色及其对应的 Spring Security Authority。 */
@Getter
public enum AdminRole {

    PLATFORM_ADMIN("1", "ROLE_PLATFORM_ADMIN"),
    TENANT_ADMIN("2", "ROLE_TENANT_ADMIN");

    private final String code;
    private final String authority;

    AdminRole(String code, String authority) {
        this.code = code;
        this.authority = authority;
    }

    /** 将数据库角色码转换为 Authority；未知值不会获得任何有效权限。 */
    public static String authorityOf(String code) {
        return Arrays.stream(values())
                .filter(role -> role.code.equals(code))
                .map(AdminRole::getAuthority)
                .findFirst()
                .orElse("ROLE_INVALID");
    }
}
