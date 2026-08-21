package com.doc.docquery.service;

import com.doc.docquery.security.ApplicationCredentialPrincipal;

/** 将外部应用凭证解析为可信 Tenant/Application 调用身份。 */
public interface ApplicationCredentialResolver {

    /** 验证凭证格式、密钥摘要及关联主体状态。 */
    ApplicationCredentialPrincipal resolve(String credential);
}
