package com.doc.docquery.service;

import com.doc.docquery.dto.CreateDocumentUploadDTO;
import com.doc.docquery.dto.CreateDocumentVersionDTO;
import com.doc.docquery.dto.DocumentRebuildSourceDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;

/**
 * N2.1 文档上传的数据库受理边界。
 *
 * <p>调用方必须先提供已可靠持久化的原文件描述；本服务只原子创建文档、
 * 版本、任务和 Outbox 事实，不连接对象存储或启动异步处理。</p>
 */
public interface DocumentUploadAcceptanceService {

    /** 在写对象前快速验证管理员、Tenant 与 KnowledgeBase 边界。 */
    void validateNewDocumentScope(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId
    );

    /** 在写对象前额外验证目标逻辑文档属于路径 KnowledgeBase 且可用。 */
    void validateNewVersionScope(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId
    );

    /** 受理首个版本并创建新的逻辑文档，支持 Tenant 范围幂等重放。 */
    DocumentUploadAcceptedVO acceptNewDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            CreateDocumentUploadDTO dto,
            StoredSourceObjectDTO source
    );

    /** 为已有逻辑文档受理下一版本，同时保持当前 active 版本不变。 */
    DocumentUploadAcceptedVO acceptNewVersion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            CreateDocumentVersionDTO dto,
            StoredSourceObjectDTO source
    );

    /** 幂等重放已存在时直接返回旧结果；首次命令返回 null。 */
    DocumentUploadAcceptedVO findRebuildReplay(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    );

    /** 读取当前 READY activeVersion 的可信原文件快照，不暴露到 HTTP。 */
    DocumentRebuildSourceDTO loadRebuildSource(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId
    );

    /** 受理已复制且内容不变的重建对象，创建候选版本、任务和 Outbox。 */
    DocumentUploadAcceptedVO acceptRebuild(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            long sourceVersionId,
            String idempotencyKey,
            StoredSourceObjectDTO copiedSource
    );
}
