package com.doc.docquery.controller;

import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadCoordinator;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** N2.2 管理面真实原文件上传接口。 */
@RestController
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequestMapping("/api/admin/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentUploadController {

    private final DocumentUploadCoordinator uploadCoordinator;

    public DocumentUploadController(DocumentUploadCoordinator uploadCoordinator) {
        this.uploadCoordinator = uploadCoordinator;
    }

    /** 首次上传：JSON metadata 与二进制 file 必须同属一个 multipart 请求。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadAcceptedVO> uploadNewDocument(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestPart("metadata") CreateDocumentUploadMetadataDTO metadata,
            @RequestPart("file") MultipartFile file
    ) {
        DocumentUploadAcceptedVO accepted = uploadCoordinator.uploadNewDocument(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                idempotencyKey,
                metadata,
                file
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /** 已有文档新版本上传；文档名称保持不变，因此不再接收 metadata Part。 */
    @PostMapping(
            path = "/{documentId}/versions",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<DocumentUploadAcceptedVO> uploadNewVersion(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @PathVariable long documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestPart("file") MultipartFile file
    ) {
        DocumentUploadAcceptedVO accepted = uploadCoordinator.uploadNewVersion(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                documentId,
                idempotencyKey,
                file
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /** 复用当前活动版本原文件并创建完整重建候选，不接收文件正文。 */
    @PostMapping("/{documentId}/rebuild")
    public ResponseEntity<DocumentUploadAcceptedVO> rebuildDocument(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @PathVariable long documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        DocumentUploadAcceptedVO accepted = uploadCoordinator.rebuildDocument(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                documentId,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    private AdminPrincipal principal(Authentication authentication) {
        return (AdminPrincipal) authentication.getPrincipal();
    }
}
