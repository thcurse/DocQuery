package com.doc.docquery.security;

import lombok.Getter;

import java.util.List;

/**
 * 服务请求完成应用授权后得到的可信、不可变查询上下文。
 *
 * <p>Tenant、Application 和 Credential 均来自已验证凭证；版本列表来自同一条
 * MySQL 快照查询。后续 BM25、KNN、canonical 读取和 Agent 工具只能消费这里
 * 固定的版本范围，不能自行重新选择 Tenant、KnowledgeBase 或 activeVersion。</p>
 */
@Getter
public class QueryAccessContext {

    private final Long credentialId;
    private final Long applicationId;
    private final Long tenantId;
    private final Long knowledgeBaseId;
    private final String grantedPermission;
    private final List<ActiveDocumentVersionSnapshot> activeVersions;
    private final String snapshotFingerprint;

    public QueryAccessContext(
            Long credentialId,
            Long applicationId,
            Long tenantId,
            Long knowledgeBaseId,
            String grantedPermission,
            List<ActiveDocumentVersionSnapshot> activeVersions,
            String snapshotFingerprint
    ) {
        this.credentialId = credentialId;
        this.applicationId = applicationId;
        this.tenantId = tenantId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.grantedPermission = grantedPermission;
        this.activeVersions = List.copyOf(activeVersions);
        this.snapshotFingerprint = snapshotFingerprint;
    }
}
