package com.doc.docquery.entity;

import java.time.LocalDateTime;

/** 应用访问知识库的授权事实；撤销采用状态和审计字段保留历史。 */
public class ApplicationGrantEntity {

    /** 数据库主键。 */
    private Long id;
    /** 冗余保存的租户隔离键，所有查询都必须带上。 */
    private Long tenantId;
    /** 被授权应用 ID。 */
    private Long applicationId;
    /** 目标知识库 ID。 */
    private Long knowledgeBaseId;
    /** READ、WRITE 或 READ_WRITE 权限码。 */
    private String permission;
    /** 授权生命周期状态码。 */
    private String status;
    /** UTC 最近授权时间。 */
    private LocalDateTime grantedAt;
    /** 最近执行授权的管理员 ID。 */
    private Long grantedBy;
    /** UTC 最近撤销时间；未撤销时为空。 */
    private LocalDateTime revokedAt;
    /** 最近执行撤销的管理员 ID。 */
    private Long revokedBy;

    public ApplicationGrantEntity() {
    }

    public ApplicationGrantEntity(
            Long id,
            Long tenantId,
            Long applicationId,
            Long knowledgeBaseId,
            String permission,
            String status,
            LocalDateTime grantedAt,
            Long grantedBy,
            LocalDateTime revokedAt,
            Long revokedBy
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.permission = permission;
        this.status = status;
        this.grantedAt = grantedAt;
        this.grantedBy = grantedBy;
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
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

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(Long revokedBy) {
        this.revokedBy = revokedBy;
    }
}
