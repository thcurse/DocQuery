package com.doc.docquery.service;

import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.DocumentDeletionAcceptedVO;

/** 管理面整个逻辑文档的不可恢复删除命令边界。 */
public interface DocumentDeletionService {

    /** 已删除且不是既有幂等命令重放时返回 null，由 HTTP 层映射为 204。 */
    DocumentDeletionAcceptedVO deleteDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    );

    DocumentDeletionAcceptedVO retryDeletion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    );
}
