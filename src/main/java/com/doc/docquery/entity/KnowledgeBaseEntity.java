package com.doc.docquery.entity;

import java.time.LocalDateTime;

/** 知识库持久化对象；文档、授权和检索都以此作为资源边界。 */
public class KnowledgeBaseEntity {

    /** 数据库主键。 */
    private Long id;
    /** 所属租户 ID。 */
    private Long tenantId;
    /** 租户内大小写敏感唯一的知识库名称。 */
    private String name;
    /** 可选知识库说明。 */
    private String description;
    /** 知识库生命周期状态码。 */
    private String status;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最后更新时间。 */
    private LocalDateTime updatedAt;

    public KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(
            Long id,
            Long tenantId,
            String name,
            String description,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
