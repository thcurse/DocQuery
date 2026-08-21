package com.doc.docquery.service.impl;

import com.doc.docquery.dto.CreateCredentialDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.entity.ApplicationEntity;
import com.doc.docquery.entity.CredentialEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.ApplicationMapper;
import com.doc.docquery.mapper.CredentialMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.CredentialService;
import com.doc.docquery.vo.CreatedCredentialVO;
import com.doc.docquery.vo.CredentialVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/**
 * 应用凭证生命周期实现。
 *
 * <p>完整凭证只在创建事务成功后返回一次；数据库仅保存公开 Key ID、
 * SHA-256 摘要和审计字段，撤销后不能恢复。</p>
 */
@Service
public class CredentialServiceImpl implements CredentialService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String REVOKED = StatusCode.REVOKED.getCode();
    private static final String TOKEN_PREFIX = "dq_app_";
    private static final int KEY_ID_BYTES = 16;
    private static final int SECRET_BYTES = 32;
    private static final int KEY_ID_PREFIX_LENGTH = 8;
    private static final int MAX_ACTIVE_CREDENTIALS = 2;
    private static final int GENERATION_ATTEMPTS = 3;

    private final TenantMapper tenantMapper;
    private final ApplicationMapper applicationMapper;
    private final CredentialMapper credentialMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialServiceImpl(
            TenantMapper tenantMapper,
            ApplicationMapper applicationMapper,
            CredentialMapper credentialMapper
    ) {
        this.tenantMapper = tenantMapper;
        this.applicationMapper = applicationMapper;
        this.credentialMapper = credentialMapper;
    }

    @Override
    @Transactional
    public CreatedCredentialVO createCredential(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            CreateCredentialDTO dto
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId, "Application ID must be positive");
        String name = normalizeName(dto.getName());

        // 锁定应用行，串行检查“最多两个有效凭证”的业务约束。
        ApplicationEntity application = applicationMapper.findByTenantAndIdForUpdate(
                new TenantResourceQueryDTO(tenantId, applicationId)
        );
        if (application == null) {
            throw applicationNotFound();
        }
        if (!ACTIVE.equals(application.getStatus())) {
            throw new BusinessException(
                    CONFLICT,
                    "APPLICATION_NOT_ACTIVE",
                    "Application must be active to create a credential"
            );
        }
        if (credentialMapper.findActiveIdsByApplicationForUpdate(applicationId, ACTIVE).size()
                >= MAX_ACTIVE_CREDENTIALS) {
            throw activeCredentialLimitReached();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CredentialEntity entity = null;
        String fullCredential = null;
        // 极低概率 Key ID 碰撞由唯一约束发现，并在当前事务内有限次重试。
        for (int attempt = 0; attempt < GENERATION_ATTEMPTS; attempt++) {
            String keyId = randomUrlSafe(KEY_ID_BYTES);
            String secret = randomUrlSafe(SECRET_BYTES);
            entity = new CredentialEntity(
                    null,
                    applicationId,
                    name,
                    keyId,
                    digest(secret),
                    ACTIVE,
                    now,
                    null,
                    null,
                    principal.id(),
                    null
            );
            try {
                if (credentialMapper.insert(entity) != 1 || entity.getId() == null) {
                    throw new IllegalStateException("Application credential was not created");
                }
                fullCredential = TOKEN_PREFIX + keyId + "." + secret;
                break;
            } catch (DuplicateKeyException exception) {
                entity = null;
            }
        }
        if (entity == null || fullCredential == null) {
            throw new IllegalStateException("Application credential could not be generated");
        }

        // fullCredential 不进入 Entity；它只通过本次创建响应暴露一次。
        return new CreatedCredentialVO(
                entity.getId(),
                entity.getApplicationId(),
                entity.getName(),
                keyIdPrefix(entity.getKeyId()),
                fullCredential,
                entity.getStatus(),
                entity.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<CredentialVO> listCredentials(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            PageQueryDTO query
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId, "Application ID must be positive");
        validatePage(query);
        requireApplication(tenantId, applicationId);

        long offset = (long) query.getPage() * query.getSize();
        List<CredentialVO> items = credentialMapper
                .findPageByApplication(applicationId, offset, query.getSize())
                .stream()
                .map(this::toVO)
                .toList();
        return new PageVO<>(
                items,
                query.getPage(),
                query.getSize(),
                credentialMapper.countByApplication(applicationId)
        );
    }

    @Override
    @Transactional
    public void revokeCredential(
            AdminPrincipal principal,
            long tenantId,
            long applicationId,
            long credentialId
    ) {
        requireActiveTenantScope(principal, tenantId);
        requirePositiveId(applicationId, "Application ID must be positive");
        requirePositiveId(credentialId, "Credential ID must be positive");
        requireApplication(tenantId, applicationId);

        CredentialEntity credential = credentialMapper.findByApplicationAndId(
                applicationId,
                credentialId
        );
        if (credential == null) {
            throw credentialNotFound();
        }
        // 撤销是不可逆但可幂等重放的操作。
        if (REVOKED.equals(credential.getStatus())) {
            return;
        }
        if (!ACTIVE.equals(credential.getStatus())) {
            throw new IllegalStateException("Credential status is invalid");
        }
        credentialMapper.revokeIfActive(
                applicationId,
                credentialId,
                LocalDateTime.now(ZoneOffset.UTC),
                principal.id(),
                ACTIVE,
                REVOKED
        );
    }

    private void requireActiveTenantScope(AdminPrincipal principal, long tenantId) {
        requirePositiveId(tenantId, "Tenant ID must be positive");
        if (principal == null || !TENANT_ADMIN.equals(principal.role())) {
            throw forbidden(
                    "TENANT_RESOURCE_MANAGEMENT_FORBIDDEN",
                    "Tenant administrator is required"
            );
        }
        if (principal.tenantId() == null || principal.tenantId().longValue() != tenantId) {
            throw forbidden("TENANT_SCOPE_FORBIDDEN", "Tenant is outside administrator scope");
        }
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            throw forbidden("TENANT_NOT_ACTIVE", "Tenant is not active");
        }
    }

    private ApplicationEntity requireApplication(long tenantId, long applicationId) {
        ApplicationEntity application = applicationMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, applicationId)
        );
        if (application == null) {
            throw applicationNotFound();
        }
        return application;
    }

    private String normalizeName(String requestedName) {
        if (requestedName == null) {
            throw validation("Credential name is required");
        }
        String name = requestedName.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 200) {
            throw validation("Credential name must contain between 1 and 200 characters");
        }
        return name;
    }

    private void validatePage(PageQueryDTO query) {
        if (query.getPage() < 0) {
            throw validation("Page must not be negative");
        }
        if (query.getSize() < 1 || query.getSize() > 100) {
            throw validation("Size must be between 1 and 100");
        }
    }

    private void requirePositiveId(long id, String message) {
        if (id < 1) {
            throw validation(message);
        }
    }

    private String randomUrlSafe(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] digest(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String keyIdPrefix(String keyId) {
        return keyId.substring(0, Math.min(KEY_ID_PREFIX_LENGTH, keyId.length()));
    }

    private CredentialVO toVO(CredentialEntity credential) {
        return new CredentialVO(
                credential.getId(),
                credential.getApplicationId(),
                credential.getName(),
                keyIdPrefix(credential.getKeyId()),
                credential.getStatus(),
                toOffset(credential.getCreatedAt()),
                toOffset(credential.getLastUsedAt()),
                toOffset(credential.getRevokedAt())
        );
    }

    private java.time.OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException forbidden(String code, String message) {
        return new BusinessException(FORBIDDEN, code, message);
    }

    private BusinessException applicationNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "APPLICATION_NOT_FOUND",
                "Application was not found"
        );
    }

    private BusinessException credentialNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "CREDENTIAL_NOT_FOUND",
                "Credential was not found"
        );
    }

    private BusinessException activeCredentialLimitReached() {
        return new BusinessException(
                CONFLICT,
                "ACTIVE_CREDENTIAL_LIMIT_REACHED",
                "Application already has two active credentials"
        );
    }
}
