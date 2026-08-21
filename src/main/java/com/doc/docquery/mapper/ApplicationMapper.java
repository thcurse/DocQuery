package com.doc.docquery.mapper;

import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.entity.ApplicationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 应用数据访问边界；除凭证认证的内部定位外，资源查询都带 Tenant 条件。 */
@Mapper
public interface ApplicationMapper {

    @Insert("""
            INSERT INTO application (
                tenant_id, code, name, environment, description, status, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{code}, #{name}, #{environment}, #{description},
                #{status}, #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApplicationEntity application);

    @Select("""
            SELECT COUNT(*)
            FROM application
            WHERE tenant_id = #{tenantId} AND code = #{code}
            """)
    long countByTenantAndCode(
            @Param("tenantId") Long tenantId,
            @Param("code") String code
    );

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   code,
                   name,
                   environment,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM application
            WHERE tenant_id = #{tenantId} AND id = #{resourceId}
            """)
    ApplicationEntity findByTenantAndId(TenantResourceQueryDTO query);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   code,
                   name,
                   environment,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM application
            WHERE tenant_id = #{tenantId} AND id = #{resourceId}
            FOR UPDATE
            """)
    /** 锁定应用行，供授权变更等需要串行化的事务使用。 */
    ApplicationEntity findByTenantAndIdForUpdate(TenantResourceQueryDTO query);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   code,
                   name,
                   environment,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM application
            WHERE id = #{applicationId}
            """)
    ApplicationEntity findById(@Param("applicationId") Long applicationId);

    @Select("SELECT COUNT(*) FROM application WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT id,
                   tenant_id AS tenantId,
                   code,
                   name,
                   environment,
                   description,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM application
            WHERE tenant_id = #{tenantId}
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ApplicationEntity> findPageByTenant(
            @Param("tenantId") Long tenantId,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE application
            SET name = #{name},
                environment = #{environment},
                description = #{description},
                status = #{status},
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    int update(ApplicationEntity application);
}
