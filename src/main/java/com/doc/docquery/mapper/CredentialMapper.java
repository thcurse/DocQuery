package com.doc.docquery.mapper;

import com.doc.docquery.entity.CredentialEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 应用凭证数据访问边界；只持久化密钥摘要和生命周期审计信息。 */
@Mapper
public interface CredentialMapper {

    @Insert("""
            INSERT INTO credential (
                application_id, name, key_id, secret_digest, status,
                created_at, last_used_at, revoked_at, created_by, revoked_by
            ) VALUES (
                #{applicationId}, #{name}, #{keyId}, #{secretDigest}, #{status},
                #{createdAt}, #{lastUsedAt}, #{revokedAt}, #{createdBy}, #{revokedBy}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CredentialEntity credential);

    @Select("""
            SELECT id
            FROM credential
            WHERE application_id = #{applicationId} AND status = #{activeStatus}
            FOR UPDATE
            """)
    List<Long> findActiveIdsByApplicationForUpdate(
            @Param("applicationId") Long applicationId,
            @Param("activeStatus") String activeStatus
    );

    @Select("SELECT COUNT(*) FROM credential WHERE application_id = #{applicationId}")
    long countByApplication(@Param("applicationId") Long applicationId);

    @Select("""
            SELECT id,
                   application_id AS applicationId,
                   name,
                   key_id AS keyId,
                   secret_digest AS secretDigest,
                   status,
                   created_at AS createdAt,
                   last_used_at AS lastUsedAt,
                   revoked_at AS revokedAt,
                   created_by AS createdBy,
                   revoked_by AS revokedBy
            FROM credential
            WHERE application_id = #{applicationId}
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CredentialEntity> findPageByApplication(
            @Param("applicationId") Long applicationId,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id,
                   application_id AS applicationId,
                   name,
                   key_id AS keyId,
                   secret_digest AS secretDigest,
                   status,
                   created_at AS createdAt,
                   last_used_at AS lastUsedAt,
                   revoked_at AS revokedAt,
                   created_by AS createdBy,
                   revoked_by AS revokedBy
            FROM credential
            WHERE application_id = #{applicationId} AND id = #{credentialId}
            """)
    CredentialEntity findByApplicationAndId(
            @Param("applicationId") Long applicationId,
            @Param("credentialId") Long credentialId
    );

    @Select("""
            SELECT id,
                   application_id AS applicationId,
                   name,
                   key_id AS keyId,
                   secret_digest AS secretDigest,
                   status,
                   created_at AS createdAt,
                   last_used_at AS lastUsedAt,
                   revoked_at AS revokedAt,
                   created_by AS createdBy,
                   revoked_by AS revokedBy
            FROM credential
            WHERE key_id = #{keyId}
            """)
    CredentialEntity findByKeyId(@Param("keyId") String keyId);

    @Update("""
            UPDATE credential
            SET status = #{revokedStatus},
                revoked_at = #{revokedAt},
                revoked_by = #{revokedBy}
            WHERE application_id = #{applicationId}
              AND id = #{credentialId}
              AND status = #{activeStatus}
            """)
    int revokeIfActive(
            @Param("applicationId") Long applicationId,
            @Param("credentialId") Long credentialId,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("revokedBy") Long revokedBy,
            @Param("activeStatus") String activeStatus,
            @Param("revokedStatus") String revokedStatus
    );

    @Update("""
            UPDATE credential
            SET last_used_at = #{usedAt}
            WHERE id = #{credentialId}
              AND (last_used_at IS NULL OR last_used_at < #{usedAt})
            """)
    int touchLastUsedAt(
            @Param("credentialId") Long credentialId,
            @Param("usedAt") LocalDateTime usedAt
    );
}
