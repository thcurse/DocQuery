package com.doc.docquery.entity;

import java.time.LocalDateTime;

/** 租户持久化对象；租户是全部管理资源和应用调用的首层隔离边界。 */
public class TenantEntity {

    /** 数据库主键。 */
    private Long id;
    /** 租户展示名称。 */
    private String name;
    /** 租户生命周期状态码。 */
    private String status;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;

    public TenantEntity() {
    }

    public TenantEntity(
            Long id,
            String name,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.id = id;
        this.name = name;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
