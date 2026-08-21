package com.doc.docquery.mapper;

import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 知识库数据访问边界；管理操作始终同时限定 Tenant 和资源 ID。 */
@Mapper
public interface KnowledgeBaseMapper {

    @Insert("""
            INSERT INTO knowledge_base (
                tenant_id, name, description, status, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{name}, #{description}, #{status}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBaseEntity knowledgeBase);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_base
            WHERE tenant_id = #{tenantId} AND name = #{name}
            """)
    long countByTenantAndName(
            @Param("tenantId") Long tenantId,
            @Param("name") String name
    );

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_base
            WHERE tenant_id = #{query.tenantId}
              AND name = #{name}
              AND id <> #{query.resourceId}
            """)
    long countByTenantAndNameExcludingId(
            @Param("query") TenantResourceQueryDTO query,
            @Param("name") String name
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   name,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM knowledge_base
            WHERE tenant_id = #{tenantId} AND id = #{resourceId}
            """)
    KnowledgeBaseEntity findByTenantAndId(TenantResourceQueryDTO query);

    @Select("""
            SELECT id, tenant_id AS tenantId, name, description, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_base
            WHERE tenant_id = #{tenantId} AND id = #{resourceId}
            FOR UPDATE
            """)
    KnowledgeBaseEntity findByTenantAndIdForUpdate(TenantResourceQueryDTO query);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   name,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM knowledge_base
            WHERE id = #{knowledgeBaseId}
            """)
    KnowledgeBaseEntity findById(@Param("knowledgeBaseId") Long knowledgeBaseId);

    @Select("SELECT COUNT(*) FROM knowledge_base WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   name,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM knowledge_base
            WHERE tenant_id = #{tenantId}
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<KnowledgeBaseEntity> findPageByTenant(
            @Param("tenantId") Long tenantId,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE knowledge_base
            SET name = #{name},
                description = #{description},
                status = #{status},
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    int update(KnowledgeBaseEntity knowledgeBase);
}
