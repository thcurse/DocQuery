package com.doc.docquery.service;

import com.doc.docquery.dto.QueryAuditPageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.ApplicationQueryAuditVO;
import com.doc.docquery.vo.PageVO;

/** Tenant Admin 查询本租户应用访问审计的只读能力。 */
public interface QueryAuditManagementService {
    PageVO<ApplicationQueryAuditVO> list(
            AdminPrincipal principal,
            long tenantId,
            QueryAuditPageQueryDTO query
    );

    ApplicationQueryAuditVO get(AdminPrincipal principal, long tenantId, long auditId);
}
