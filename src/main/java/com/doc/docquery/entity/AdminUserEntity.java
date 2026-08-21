package com.doc.docquery.entity;

import java.time.LocalDateTime;

/** 管理员账号持久化对象；平台管理员的 {@code tenantId} 为空。 */
public class AdminUserEntity {

    /** 数据库主键。 */
    private Long id;
    /** 租户管理员所属租户；平台管理员为空。 */
    private Long tenantId;
    /** 规范化后的登录名。 */
    private String loginName;
    /** 带算法标识的密码哈希，绝不保存明文。 */
    private String passwordHash;
    /** 管理角色状态码。 */
    private String role;
    /** 账号生命周期状态码。 */
    private String status;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;

    public AdminUserEntity() {
    }

    public AdminUserEntity(
            Long id,
            Long tenantId,
            String loginName,
            String passwordHash,
            String role,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.loginName = loginName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getLoginName() {
        return loginName;
    }

    public void setLoginName(String loginName) {
        this.loginName = loginName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
