package com.doc.docquery.service.impl;

import com.doc.docquery.dto.ActiveDocumentVersionDTO;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.enums.GrantPermission;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.security.ApplicationCredentialAuthenticationException;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.KnowledgeBaseAccessContext;
import com.doc.docquery.security.KnowledgeBaseAccessDeniedException;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.security.QueryAccessException;
import com.doc.docquery.service.ApplicationCredentialResolver;
import com.doc.docquery.service.KnowledgeBaseAccessAuthorizer;
import com.doc.docquery.service.QueryAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static com.doc.docquery.security.QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID;
import static com.doc.docquery.security.QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE;

/**
 * 服务请求的可信查询范围实现。
 *
 * <p>先复用 N1 已验收的凭证和 Grant 组件，再以单条 SQL 固定版本集合。所有
 * N1 内部精确原因在这里收敛为服务面允许暴露的 401/404 语义。</p>
 */
@Service
public class QueryAccessServiceImpl implements QueryAccessService {

    private static final String BEARER_PREFIX = "bearer ";
    private static final String SNAPSHOT_VERSION = "query-active-snapshot-v1";

    private final ApplicationCredentialResolver credentialResolver;
    private final KnowledgeBaseAccessAuthorizer accessAuthorizer;
    private final DocumentMapper documentMapper;

    public QueryAccessServiceImpl(
            ApplicationCredentialResolver credentialResolver,
            KnowledgeBaseAccessAuthorizer accessAuthorizer,
            DocumentMapper documentMapper
    ) {
        this.credentialResolver = credentialResolver;
        this.accessAuthorizer = accessAuthorizer;
        this.documentMapper = documentMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public QueryAccessContext authorizeAndSnapshot(
            String authorizationHeader,
            long knowledgeBaseId
    ) {
        ApplicationCredentialPrincipal principal;
        try {
            principal = credentialResolver.resolve(extractBearerCredential(authorizationHeader));
        } catch (ApplicationCredentialAuthenticationException exception) {
            // 撤销、停用、未知 Key 和错误 Secret 对服务调用方使用相同认证失败。
            throw invalidCredential();
        }

        KnowledgeBaseAccessContext accessContext;
        try {
            accessContext = accessAuthorizer.authorize(
                    principal,
                    knowledgeBaseId,
                    GrantPermission.READ
            );
        } catch (KnowledgeBaseAccessDeniedException exception) {
            // 不向应用区分不存在、跨租户、停用、无 Grant 或 WRITE-only。
            throw new QueryAccessException(
                    KNOWLEDGE_BASE_NOT_AVAILABLE,
                    "KnowledgeBase is not available to this application"
            );
        }

        List<ActiveDocumentVersionDTO> activeVersions =
                documentMapper.findActiveVersionSnapshot(
                        accessContext.getTenantId(),
                        accessContext.getKnowledgeBaseId(),
                        DocumentStatus.ACTIVE.getCode(),
                        DocumentVersionStatus.READY.getCode()
                );
        String fingerprint = snapshotFingerprint(
                accessContext.getTenantId(),
                accessContext.getKnowledgeBaseId(),
                activeVersions
        );
        List<ActiveDocumentVersionSnapshot> immutableVersions = activeVersions.stream()
                .map(version -> new ActiveDocumentVersionSnapshot(
                        version.getDocumentId(),
                        version.getDocumentVersionId(),
                        version.getVersionNo(),
                        version.getDocumentName()
                ))
                .toList();
        return new QueryAccessContext(
                accessContext.getCredentialId(),
                accessContext.getApplicationId(),
                accessContext.getTenantId(),
                accessContext.getKnowledgeBaseId(),
                accessContext.getGrantedPermission(),
                immutableVersions,
                fingerprint
        );
    }

    private String extractBearerCredential(String authorizationHeader) {
        if (authorizationHeader == null) {
            throw invalidCredential();
        }
        String normalized = authorizationHeader.trim();
        if (normalized.length() <= BEARER_PREFIX.length()
                || !normalized.substring(0, BEARER_PREFIX.length())
                .toLowerCase(Locale.ROOT).equals(BEARER_PREFIX)) {
            throw invalidCredential();
        }
        String credential = normalized.substring(BEARER_PREFIX.length());
        if (credential.isBlank() || credential.chars().anyMatch(Character::isWhitespace)) {
            throw invalidCredential();
        }
        return credential;
    }

    private String snapshotFingerprint(
            Long tenantId,
            Long knowledgeBaseId,
            List<ActiveDocumentVersionDTO> activeVersions
    ) {
        StringBuilder canonical = new StringBuilder()
                .append(SNAPSHOT_VERSION).append('\n')
                .append(tenantId).append('\n')
                .append(knowledgeBaseId).append('\n');
        for (ActiveDocumentVersionDTO version : activeVersions) {
            canonical.append(version.getDocumentId())
                    .append(':')
                    .append(version.getDocumentVersionId())
                    .append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private QueryAccessException invalidCredential() {
        return new QueryAccessException(
                APPLICATION_CREDENTIAL_INVALID,
                "Application credential is invalid"
        );
    }
}
