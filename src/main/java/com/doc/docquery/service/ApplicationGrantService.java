package com.doc.docquery.service;

import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpsertApplicationGrantDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.ApplicationGrantVO;
import com.doc.docquery.vo.PageVO;

/** 租户管理员维护应用与知识库授权关系的业务边界。 */
public interface ApplicationGrantService {

    /** 新增授权，或恢复/修改已有授权。 */
    ApplicationGrantVO upsertGrant(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            long knowledgeBaseId,
            UpsertApplicationGrantDTO dto
    );

    /** 分页列出某应用拥有的知识库授权。 */
    PageVO<ApplicationGrantVO> listByApplication(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            PageQueryDTO query
    );

    /** 分页列出可访问某知识库的应用授权。 */
    PageVO<ApplicationGrantVO> listByKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            PageQueryDTO query
    );

    /** 撤销授权但保留审计事实，以便后续明确恢复。 */
    void revokeGrant(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            long knowledgeBaseId
    );
}
