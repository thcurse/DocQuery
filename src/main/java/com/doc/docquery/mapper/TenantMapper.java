package com.doc.docquery.mapper;

import com.doc.docquery.entity.TenantEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 租户表的 MyBatis 数据访问边界。 */
@Mapper
public interface TenantMapper {

    @Insert("""
            INSERT INTO tenant (name, status, created_at, updated_at)
            VALUES (#{name}, #{status}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TenantEntity tenant);

    @Select("""
            SELECT id, name, status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM tenant
            WHERE id = #{tenantId}
            """)
    TenantEntity findById(@Param("tenantId") Long tenantId);

    @Select("""
            SELECT id, name, status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM tenant
            WHERE id = #{tenantId}
            FOR UPDATE
            """)
    TenantEntity findByIdForUpdate(@Param("tenantId") Long tenantId);

    @Select("SELECT COUNT(*) FROM tenant")
    long countAll();

    @Select("""
            SELECT id, name, status,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM tenant
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<TenantEntity> findPage(
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE tenant
            SET name = #{name}, status = #{status}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int update(TenantEntity tenant);
}
