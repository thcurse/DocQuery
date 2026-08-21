package com.doc.docquery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员身份联表查询结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminIdentityDTO {

    /** 管理员ID。 */
    private Long id;

    /** 所属租户ID；平台管理员为空。 */
    private Long tenantId;

    /** 登录名。 */
    private String loginName;

    /** 密码哈希；Session复查时为空。 */
    private String passwordHash;

    /** 管理员角色。 */
    private String role;

    /** 管理员状态。 */
    private String adminStatus;

    /** 租户状态；平台管理员为空。 */
    private String tenantStatus;

    /** 账号版本时间；状态或密码变化后用于使旧 Session 失效。 */
    private LocalDateTime accountUpdatedAt;
}
