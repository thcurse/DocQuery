package com.doc.docquery.service;

import com.doc.docquery.dto.CreateTenantDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateTenantDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.CreatedTenantVO;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.TenantVO;

/** 平台管理员维护租户及初始租户管理员的业务边界。 */
public interface TenantManagementService {

    /** 原子创建租户及其首个租户管理员。 */
    CreatedTenantVO createTenant(AdminPrincipal principal, CreateTenantDTO dto);

    /** 按调用者角色分页列出其可见租户。 */
    PageVO<TenantVO> listTenants(AdminPrincipal principal, PageQueryDTO query);

    /** 读取调用者范围内的租户。 */
    TenantVO getTenant(AdminPrincipal principal, long tenantId);

    /** 修改租户名称或启停状态。 */
    TenantVO updateTenant(AdminPrincipal principal, long tenantId, UpdateTenantDTO dto);
}
