package com.doc.docquery.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 从应用凭证解析出的可信应用身份。
 */
@Getter
@AllArgsConstructor
public class ApplicationCredentialPrincipal {

    private final Long credentialId;
    private final Long applicationId;
    private final Long tenantId;
}
