package com.doc.docquery.service;

import com.doc.docquery.dto.CreateCredentialDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.CreatedCredentialVO;
import com.doc.docquery.vo.CredentialVO;
import com.doc.docquery.vo.PageVO;

/** 应用凭证创建、查询和撤销的业务边界。 */
public interface CredentialService {

    /** 创建凭证并仅在本次响应返回完整密钥。 */
    CreatedCredentialVO createCredential(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            CreateCredentialDTO dto
    );

    /** 分页列出凭证的非敏感元数据。 */
    PageVO<CredentialVO> listCredentials(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            PageQueryDTO query
    );

    /** 不可逆地撤销指定凭证。 */
    void revokeCredential(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            long credentialId
    );
}
