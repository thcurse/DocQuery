package com.doc.docquery.service;

import com.doc.docquery.dto.CreateApplicationDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateApplicationDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.ApplicationVO;
import com.doc.docquery.vo.PageVO;

/** 租户应用元数据管理业务边界。 */
public interface ApplicationService {

    /** 在当前租户内创建应用。 */
    ApplicationVO createApplication(
            AdminPrincipal principal,
            long tenantId,
            CreateApplicationDTO dto
    );

    /** 分页列出当前租户的应用。 */
    PageVO<ApplicationVO> listApplications(
            AdminPrincipal principal,
            long tenantId,
            PageQueryDTO query
    );

    /** 读取当前租户内的单个应用。 */
    ApplicationVO getApplication(
            AdminPrincipal principal,
            long tenantId,
            long applicationId
    );

    /** 修改应用可变元数据及启停状态；稳定编码不可修改。 */
    ApplicationVO updateApplication(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            UpdateApplicationDTO dto
    );
}
