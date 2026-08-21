package com.doc.docquery.enums;

import lombok.Getter;

import java.util.Arrays;

/** 应用对知识库的授权等级；READ_WRITE 同时包含读写能力。 */
@Getter
public enum GrantPermission {

    READ("1"),
    WRITE("2"),
    READ_WRITE("3");

    private final String code;

    GrantPermission(String code) {
        this.code = code;
    }

    /** 判断当前授权是否覆盖调用所需权限。 */
    public boolean allows(GrantPermission requiredPermission) {
        if (requiredPermission == null) {
            return false;
        }
        return this == READ_WRITE || this == requiredPermission;
    }

    /** 解析持久化权限码，拒绝未知值以避免权限被意外放宽。 */
    public static GrantPermission fromCode(String code) {
        return Arrays.stream(values())
                .filter(permission -> permission.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported grant permission"));
    }
}
