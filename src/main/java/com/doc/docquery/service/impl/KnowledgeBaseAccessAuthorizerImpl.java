package com.doc.docquery.service.impl;

import com.doc.docquery.entity.ApplicationEntity;
import com.doc.docquery.entity.ApplicationGrantEntity;
import com.doc.docquery.entity.CredentialEntity;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.GrantPermission;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.mapper.ApplicationGrantMapper;
import com.doc.docquery.mapper.ApplicationMapper;
import com.doc.docquery.mapper.CredentialMapper;
import com.doc.docquery.mapper.KnowledgeBaseMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.security.KnowledgeBaseAccessContext;
import com.doc.docquery.security.KnowledgeBaseAccessDeniedException;
import com.doc.docquery.service.KnowledgeBaseAccessAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.APPLICATION_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.CREDENTIAL_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.GRANT_MISSING;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.GRANT_REVOKED;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.INSUFFICIENT_PERMISSION;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.INVALID_CONTEXT;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.KNOWLEDGE_BASE_UNAVAILABLE;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.TENANT_MISMATCH;
import static com.doc.docquery.security.KnowledgeBaseAccessDeniedException.Reason.TENANT_UNAVAILABLE;

/**
 * 应用访问知识库的统一授权实现。
 *
 * <p>依次验证可信凭证身份、租户、应用、知识库和 Grant；任何 Tenant 不一致
 * 都拒绝，避免只凭资源 ID 穿透隔离边界。</p>
 */
@Service
public class KnowledgeBaseAccessAuthorizerImpl implements KnowledgeBaseAccessAuthorizer {

    private static final String ACTIVE = StatusCode.ACTIVE.getCode();

    private final CredentialMapper credentialMapper;
    private final ApplicationMapper applicationMapper;
    private final TenantMapper tenantMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ApplicationGrantMapper applicationGrantMapper;

    public KnowledgeBaseAccessAuthorizerImpl(
            CredentialMapper credentialMapper,
            ApplicationMapper applicationMapper,
            TenantMapper tenantMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            ApplicationGrantMapper applicationGrantMapper
    ) {
        this.credentialMapper = credentialMapper;
        this.applicationMapper = applicationMapper;
        this.tenantMapper = tenantMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.applicationGrantMapper = applicationGrantMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseAccessContext authorize(
            ApplicationCredentialPrincipal principal,
            long knowledgeBaseId,
            GrantPermission requiredPermission
    ) {
        if (principal == null
                || principal.getCredentialId() == null
                || principal.getApplicationId() == null
                || principal.getTenantId() == null
                || knowledgeBaseId < 1
                || requiredPermission == null) {
            throw denied(INVALID_CONTEXT, "KnowledgeBase access context is invalid");
        }

        // 不信任 Principal 中除稳定 ID 外的状态，逐层从数据库复核主体。
        CredentialEntity credential = credentialMapper.findByApplicationAndId(
                principal.getApplicationId(),
                principal.getCredentialId()
        );
        if (credential == null || !ACTIVE.equals(credential.getStatus())) {
            throw denied(CREDENTIAL_UNAVAILABLE, "Application credential is unavailable");
        }

        ApplicationEntity application = applicationMapper.findById(
                principal.getApplicationId()
        );
        if (application == null
                || !principal.getTenantId().equals(application.getTenantId())
                || !ACTIVE.equals(application.getStatus())) {
            throw denied(APPLICATION_UNAVAILABLE, "Application is unavailable");
        }

        TenantEntity tenant = tenantMapper.findById(principal.getTenantId());
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            throw denied(TENANT_UNAVAILABLE, "Tenant is unavailable");
        }

        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.findById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw denied(KNOWLEDGE_BASE_UNAVAILABLE, "KnowledgeBase is unavailable");
        }
        // 资源存在但跨租户时必须明确拒绝，不能继续匹配其他租户的 Grant。
        if (!principal.getTenantId().equals(knowledgeBase.getTenantId())) {
            throw denied(TENANT_MISMATCH, "KnowledgeBase belongs to another tenant");
        }
        if (!ACTIVE.equals(knowledgeBase.getStatus())) {
            throw denied(KNOWLEDGE_BASE_UNAVAILABLE, "KnowledgeBase is unavailable");
        }

        ApplicationGrantEntity grant = applicationGrantMapper.find(
                principal.getTenantId(),
                principal.getApplicationId(),
                knowledgeBaseId
        );
        if (grant == null) {
            throw denied(GRANT_MISSING, "Application grant is missing");
        }
        if (!ACTIVE.equals(grant.getStatus())) {
            throw denied(GRANT_REVOKED, "Application grant is not active");
        }

        // 数据库中的未知权限码按拒绝处理，绝不采用宽松默认值。
        GrantPermission grantedPermission;
        try {
            grantedPermission = GrantPermission.fromCode(grant.getPermission());
        } catch (IllegalArgumentException exception) {
            throw denied(INSUFFICIENT_PERMISSION, "Application grant is invalid");
        }
        if (!grantedPermission.allows(requiredPermission)) {
            throw denied(INSUFFICIENT_PERMISSION, "Application permission is insufficient");
        }

        return new KnowledgeBaseAccessContext(
                credential.getId(),
                application.getId(),
                tenant.getId(),
                knowledgeBase.getId(),
                grantedPermission.getCode()
        );
    }

    private KnowledgeBaseAccessDeniedException denied(
            KnowledgeBaseAccessDeniedException.Reason reason,
            String message
    ) {
        return new KnowledgeBaseAccessDeniedException(reason, message);
    }
}
