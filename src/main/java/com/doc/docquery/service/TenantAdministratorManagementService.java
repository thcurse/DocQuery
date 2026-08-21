package com.doc.docquery.service;

import com.doc.docquery.dto.CreateTenantAdministratorDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.ResetTenantAdministratorPasswordDTO;
import com.doc.docquery.dto.UpdateTenantAdministratorDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.TenantAdministratorVO;

/** 平台管理员维护租户管理员账号的业务边界。 */
public interface TenantAdministratorManagementService {

    PageVO<TenantAdministratorVO> listAdministrators(
            AdminPrincipal principal,
            long tenantId,
            PageQueryDTO query
    );

    TenantAdministratorVO createAdministrator(
            AdminPrincipal principal,
            long tenantId,
            CreateTenantAdministratorDTO dto
    );

    TenantAdministratorVO updateAdministrator(
            AdminPrincipal principal,
            long tenantId,
            long administratorId,
            UpdateTenantAdministratorDTO dto
    );

    void resetPassword(
            AdminPrincipal principal,
            long tenantId,
            long administratorId,
            ResetTenantAdministratorPasswordDTO dto
    );
}
