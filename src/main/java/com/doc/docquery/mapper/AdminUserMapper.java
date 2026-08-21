package com.doc.docquery.mapper;

import com.doc.docquery.dto.AdminIdentityDTO;
import com.doc.docquery.entity.AdminUserEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 管理员账号数据访问边界，包含登录查询和会话状态复核。 */
@Mapper
public interface AdminUserMapper {

    @Select("SELECT COUNT(*) FROM admin_user")
    long countAll();

    @Select("SELECT COUNT(*) FROM admin_user WHERE login_name = #{loginName}")
    long countByLoginName(@Param("loginName") String loginName);

    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class, id = true),
            @Arg(column = "tenant_id", javaType = Long.class),
            @Arg(column = "login_name", javaType = String.class),
            @Arg(column = "password_hash", javaType = String.class),
            @Arg(column = "role", javaType = String.class),
            @Arg(column = "admin_status", javaType = String.class),
            @Arg(column = "tenant_status", javaType = String.class),
            @Arg(column = "account_updated_at", javaType = LocalDateTime.class)
    })
    @Select("""
            SELECT
                au.id,
                au.tenant_id,
                au.login_name,
                au.password_hash,
                au.role,
                au.status AS admin_status,
                t.status AS tenant_status,
                au.updated_at AS account_updated_at
            FROM admin_user au
            LEFT JOIN tenant t ON t.id = au.tenant_id
            WHERE au.login_name = #{loginName}
            """)
    AdminIdentityDTO findForLogin(@Param("loginName") String loginName);

    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class, id = true),
            @Arg(column = "tenant_id", javaType = Long.class),
            @Arg(column = "login_name", javaType = String.class),
            @Arg(column = "password_hash", javaType = String.class),
            @Arg(column = "role", javaType = String.class),
            @Arg(column = "admin_status", javaType = String.class),
            @Arg(column = "tenant_status", javaType = String.class),
            @Arg(column = "account_updated_at", javaType = LocalDateTime.class)
    })
    @Select("""
            SELECT
                au.id,
                au.tenant_id,
                au.login_name,
                NULL AS password_hash,
                au.role,
                au.status AS admin_status,
                t.status AS tenant_status,
                au.updated_at AS account_updated_at
            FROM admin_user au
            LEFT JOIN tenant t ON t.id = au.tenant_id
            WHERE au.id = #{adminId}
            """)
    AdminIdentityDTO findForSession(@Param("adminId") Long adminId);

    @Insert("""
            INSERT INTO admin_user (
                tenant_id, login_name, password_hash, role, status, created_at, updated_at
            ) VALUES (
                #{tenantId}, #{loginName}, #{passwordHash}, #{role}, #{status},
                #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AdminUserEntity adminUser);

    @Select("""
            SELECT id, tenant_id AS tenantId, login_name AS loginName,
                   NULL AS passwordHash, role, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM admin_user
            WHERE tenant_id = #{tenantId} AND role = #{role}
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AdminUserEntity> findTenantAdministrators(
            @Param("tenantId") long tenantId,
            @Param("role") String role,
            @Param("offset") long offset,
            @Param("limit") int limit
    );

    @Select("""
            SELECT COUNT(*)
            FROM admin_user
            WHERE tenant_id = #{tenantId} AND role = #{role}
            """)
    long countTenantAdministrators(
            @Param("tenantId") long tenantId,
            @Param("role") String role
    );

    @Select("""
            SELECT id, tenant_id AS tenantId, login_name AS loginName,
                   NULL AS passwordHash, role, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM admin_user
            WHERE id = #{administratorId}
              AND tenant_id = #{tenantId}
              AND role = #{role}
            """)
    AdminUserEntity findTenantAdministrator(
            @Param("tenantId") long tenantId,
            @Param("administratorId") long administratorId,
            @Param("role") String role
    );

    @Select("""
            SELECT COUNT(*)
            FROM admin_user
            WHERE tenant_id = #{tenantId}
              AND role = #{role}
              AND status = #{status}
            """)
    long countTenantAdministratorsByStatus(
            @Param("tenantId") long tenantId,
            @Param("role") String role,
            @Param("status") String status
    );

    @Update("""
            UPDATE admin_user
            SET status = #{status}, updated_at = #{updatedAt}
            WHERE id = #{administratorId}
              AND tenant_id = #{tenantId}
              AND role = #{role}
            """)
    int updateTenantAdministratorStatus(
            @Param("tenantId") long tenantId,
            @Param("administratorId") long administratorId,
            @Param("role") String role,
            @Param("status") String status,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
            UPDATE admin_user
            SET password_hash = #{passwordHash}, updated_at = #{updatedAt}
            WHERE id = #{administratorId}
              AND tenant_id = #{tenantId}
              AND role = #{role}
            """)
    int updateTenantAdministratorPassword(
            @Param("tenantId") long tenantId,
            @Param("administratorId") long administratorId,
            @Param("role") String role,
            @Param("passwordHash") String passwordHash,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
