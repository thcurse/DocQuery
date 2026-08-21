package com.doc.docquery.mapper;

import com.doc.docquery.entity.ApplicationGrantEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 应用与知识库授权事实的数据访问边界。 */
@Mapper
public interface ApplicationGrantMapper {

    @Insert("""
            INSERT INTO application_grant (
                tenant_id, application_id, knowledge_base_id, permission, status,
                granted_at, granted_by, revoked_at, revoked_by
            ) VALUES (
                #{tenantId}, #{applicationId}, #{knowledgeBaseId}, #{permission}, #{status},
                #{grantedAt}, #{grantedBy}, #{revokedAt}, #{revokedBy}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApplicationGrantEntity grant);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   application_id AS applicationId,
                   knowledge_base_id AS knowledgeBaseId,
                   permission,
                   status,
                   granted_at AS grantedAt,
                   granted_by AS grantedBy,
                   revoked_at AS revokedAt,
                   revoked_by AS revokedBy
            FROM application_grant
            WHERE tenant_id = #{tenantId}
              AND application_id = #{applicationId}
              AND knowledge_base_id = #{knowledgeBaseId}
            """)
    ApplicationGrantEntity find(
            @Param("tenantId") Long tenantId,
            @Param("applicationId") Long applicationId,
            @Param("knowledgeBaseId") Long knowledgeBaseId
    );

    @Update("""
            UPDATE application_grant
            SET permission = #{permission},
                status = #{status},
                granted_at = #{grantedAt},
                granted_by = #{grantedBy},
                revoked_at = #{revokedAt},
                revoked_by = #{revokedBy}
            WHERE tenant_id = #{tenantId}
              AND application_id = #{applicationId}
              AND knowledge_base_id = #{knowledgeBaseId}
            """)
    int update(ApplicationGrantEntity grant);

    @Update("""
            UPDATE application_grant
            SET status = #{revokedStatus},
                revoked_at = #{revokedAt},
                revoked_by = #{revokedBy}
            WHERE tenant_id = #{tenantId}
              AND application_id = #{applicationId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND status = #{activeStatus}
            """)
    int revokeIfActive(
            @Param("tenantId") Long tenantId,
            @Param("applicationId") Long applicationId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("revokedBy") Long revokedBy,
            @Param("activeStatus") String activeStatus,
            @Param("revokedStatus") String revokedStatus
    );

    @Select("""
            SELECT COUNT(*)
            FROM application_grant
            WHERE tenant_id = #{tenantId} AND application_id = #{applicationId}
            """)
    long countByApplication(
            @Param("tenantId") Long tenantId,
            @Param("applicationId") Long applicationId
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   application_id AS applicationId,
                   knowledge_base_id AS knowledgeBaseId,
                   permission,
                   status,
                   granted_at AS grantedAt,
                   granted_by AS grantedBy,
                   revoked_at AS revokedAt,
                   revoked_by AS revokedBy
            FROM application_grant
            WHERE tenant_id = #{tenantId} AND application_id = #{applicationId}
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ApplicationGrantEntity> findPageByApplication(
            @Param("tenantId") Long tenantId,
            @Param("applicationId") Long applicationId,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM application_grant
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId}
            """)
    long countByKnowledgeBase(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   application_id AS applicationId,
                   knowledge_base_id AS knowledgeBaseId,
                   permission,
                   status,
                   granted_at AS grantedAt,
                   granted_by AS grantedBy,
                   revoked_at AS revokedAt,
                   revoked_by AS revokedBy
            FROM application_grant
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{knowledgeBaseId}
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ApplicationGrantEntity> findPageByKnowledgeBase(
            @Param("tenantId") Long tenantId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("offset") long offset,
            @Param("limit") int limit
    );
}
