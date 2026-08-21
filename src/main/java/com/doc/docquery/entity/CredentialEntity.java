package com.doc.docquery.entity;

import java.time.LocalDateTime;

/** 应用凭证持久化对象；只保存 Key ID 和密钥摘要，不保存可恢复密钥。 */
public class CredentialEntity {

    /** 数据库主键。 */
    private Long id;
    /** 凭证所属应用 ID。 */
    private Long applicationId;
    /** 便于管理员识别用途的凭证名称。 */
    private String name;
    /** 公开的凭证查找标识。 */
    private String keyId;
    /** 对密钥做 SHA-256 后的固定长度摘要。 */
    private byte[] secretDigest;
    /** 凭证生命周期状态码。 */
    private String status;
    /** UTC 创建时间。 */
    private LocalDateTime createdAt;
    /** UTC 最近成功使用时间。 */
    private LocalDateTime lastUsedAt;
    /** UTC 撤销时间；未撤销时为空。 */
    private LocalDateTime revokedAt;
    /** 创建该凭证的管理员 ID。 */
    private Long createdBy;
    /** 撤销该凭证的管理员 ID。 */
    private Long revokedBy;

    public CredentialEntity() {
    }

    public CredentialEntity(
            Long id,
            Long applicationId,
            String name,
            String keyId,
            byte[] secretDigest,
            String status,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt,
            LocalDateTime revokedAt,
            Long createdBy,
            Long revokedBy
    ) {
        this.id = id;
        this.applicationId = applicationId;
        this.name = name;
        this.keyId = keyId;
        this.secretDigest = secretDigest;
        this.status = status;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
        this.createdBy = createdBy;
        this.revokedBy = revokedBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public byte[] getSecretDigest() {
        return secretDigest;
    }

    public void setSecretDigest(byte[] secretDigest) {
        this.secretDigest = secretDigest;
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

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(Long revokedBy) {
        this.revokedBy = revokedBy;
    }
}
