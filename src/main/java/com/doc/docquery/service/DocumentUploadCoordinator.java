package com.doc.docquery.service;

import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.springframework.web.multipart.MultipartFile;

/** 管理面真实文件上传、对象保存和 N2.1 数据库受理的协调边界。 */
public interface DocumentUploadCoordinator {

    /** 保存首个原文件并创建逻辑文档。 */
    DocumentUploadAcceptedVO uploadNewDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            String idempotencyKey,
            CreateDocumentUploadMetadataDTO metadata,
            MultipartFile file
    );

    /** 保存已有逻辑文档的新版本原文件。 */
    DocumentUploadAcceptedVO uploadNewVersion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey,
            MultipartFile file
    );

    /** 复用当前活动版本原文件，创建一次完整处理链路的重建候选。 */
    DocumentUploadAcceptedVO rebuildDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    );
}
