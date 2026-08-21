package com.doc.docquery.entity;

import java.time.LocalDateTime;

/** 可信业务应用持久化对象；每个应用严格归属于一个租户。 */
public class ApplicationEntity {

    /** 数据库主键。 */
    private Long id;
    /** 所属租户 ID。 */
    private Long tenantId;
    /** 租户内唯一、规范化为小写的稳定应用编码。 */
    private String code;
    /** 应用展示名称。 */
    private String name;
    /** DEVELOPMENT、TESTING 或 PRODUCTION。 */
    private String environment;
    /** 可选应用说明。 */
    private String description;
    /** 应用生命周期状态码。 */
    private String status;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;

    public ApplicationEntity() {
    }

    public ApplicationEntity(
            Long id,
            Long tenantId,
            String code,
            String name,
            String environment,
            String description,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.environment = environment;
        this.description = description;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
