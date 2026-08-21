package com.doc.docquery.service.impl;

import com.doc.docquery.entity.ApplicationEntity;
import com.doc.docquery.entity.CredentialEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.mapper.ApplicationMapper;
import com.doc.docquery.mapper.CredentialMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.ApplicationCredentialAuthenticationException;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.service.ApplicationCredentialResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.APPLICATION_UNAVAILABLE;
import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.CREDENTIAL_REVOKED;
import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.INVALID_CREDENTIAL;
import static com.doc.docquery.security.ApplicationCredentialAuthenticationException.Reason.TENANT_UNAVAILABLE;

/**
 * 应用凭证解析实现。
 *
 * <p>先用公开 Key ID 定位候选凭证，再以常量时间比较密钥摘要，并逐层检查
 * 凭证、应用和租户状态；对外不区分不存在与密钥错误。</p>
 */
@Service
public class ApplicationCredentialResolverImpl implements ApplicationCredentialResolver {

    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String TOKEN_PREFIX = "dq_app_";
    private static final int KEY_ID_LENGTH = 22;
    private static final int SECRET_LENGTH = 43;
    private static final byte[] UNKNOWN_DIGEST = new byte[32];
    private static final Pattern URL_SAFE_PART = Pattern.compile("[A-Za-z0-9_-]+");

    private final CredentialMapper credentialMapper;
    private final ApplicationMapper applicationMapper;
    private final TenantMapper tenantMapper;

    public ApplicationCredentialResolverImpl(
            CredentialMapper credentialMapper,
            ApplicationMapper applicationMapper,
            TenantMapper tenantMapper
    ) {
        this.credentialMapper = credentialMapper;
        this.applicationMapper = applicationMapper;
        this.tenantMapper = tenantMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationCredentialPrincipal resolve(String credentialValue) {
        if (!hasValidFormat(credentialValue)) {
            throw invalidCredential();
        }
        int separator = credentialValue.indexOf('.', TOKEN_PREFIX.length());
        String keyId = credentialValue.substring(TOKEN_PREFIX.length(), separator);
        String secret = credentialValue.substring(separator + 1);
        byte[] actualDigest = digest(secret);

        CredentialEntity credential = credentialMapper.findByKeyId(keyId);
        // 未命中时仍执行固定长度摘要比较，减少凭证存在性带来的时序差异。
        byte[] expectedDigest = credential == null
                ? UNKNOWN_DIGEST
                : credential.getSecretDigest();
        boolean secretMatches = MessageDigest.isEqual(expectedDigest, actualDigest);
        if (credential == null || !secretMatches) {
            throw invalidCredential();
        }
        if (!ACTIVE.equals(credential.getStatus())) {
            throw new ApplicationCredentialAuthenticationException(
                    CREDENTIAL_REVOKED,
                    "Application credential is not active"
            );
        }

        // 凭证有效不代表主体有效；应用或租户停用必须立即阻断调用。
        ApplicationEntity application = applicationMapper.findById(
                credential.getApplicationId()
        );
        if (application == null || !ACTIVE.equals(application.getStatus())) {
            throw new ApplicationCredentialAuthenticationException(
                    APPLICATION_UNAVAILABLE,
                    "Application credential is unavailable"
            );
        }
        TenantEntity tenant = tenantMapper.findById(application.getTenantId());
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            throw new ApplicationCredentialAuthenticationException(
                    TENANT_UNAVAILABLE,
                    "Application credential is unavailable"
            );
        }

        return new ApplicationCredentialPrincipal(
                credential.getId(),
                application.getId(),
                tenant.getId()
        );
    }

    private boolean hasValidFormat(String credentialValue) {
        if (credentialValue == null) {
            return false;
        }
        int separator = credentialValue.indexOf('.', TOKEN_PREFIX.length());
        if (!credentialValue.startsWith(TOKEN_PREFIX)
                || separator < 0
                || separator != credentialValue.lastIndexOf('.')) {
            return false;
        }
        String keyId = credentialValue.substring(TOKEN_PREFIX.length(), separator);
        String secret = credentialValue.substring(separator + 1);
        return keyId.length() == KEY_ID_LENGTH
                && secret.length() == SECRET_LENGTH
                && URL_SAFE_PART.matcher(keyId).matches()
                && URL_SAFE_PART.matcher(secret).matches();
    }

    private byte[] digest(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ApplicationCredentialAuthenticationException invalidCredential() {
        return new ApplicationCredentialAuthenticationException(
                INVALID_CREDENTIAL,
                "Application credential is invalid"
        );
    }
}
