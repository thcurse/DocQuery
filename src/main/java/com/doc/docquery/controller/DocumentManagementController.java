package com.doc.docquery.controller;

import com.doc.docquery.dto.DocumentManagementPageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentDeletionService;
import com.doc.docquery.service.DocumentManagementService;
import com.doc.docquery.vo.DocumentDeletionAcceptedVO;
import com.doc.docquery.vo.DocumentManagementVO;
import com.doc.docquery.vo.DocumentVersionVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** N4.1 文档和版本管理查询接口。 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentManagementController {

    private final DocumentManagementService managementService;
    private final DocumentDeletionService deletionService;

    public DocumentManagementController(
            DocumentManagementService managementService,
            DocumentDeletionService deletionService
    ) {
        this.managementService = managementService;
        this.deletionService = deletionService;
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<DocumentDeletionAcceptedVO> delete(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @PathVariable long documentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        DocumentDeletionAcceptedVO accepted = deletionService.deleteDocument(
                principal(authentication), tenantId, knowledgeBaseId, documentId, idempotencyKey
        );
        return accepted == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.accepted().body(accepted);
    }

    @PostMapping("/{documentId}/deletion/retry")
    public ResponseEntity<DocumentDeletionAcceptedVO> retryDeletion(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @PathVariable long documentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.accepted().body(deletionService.retryDeletion(
                principal(authentication), tenantId, knowledgeBaseId, documentId, idempotencyKey
        ));
    }

    @GetMapping
    public PageVO<DocumentManagementVO> list(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String documentStatus,
            @RequestParam(required = false) String latestVersionStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        DocumentManagementPageQueryDTO query = new DocumentManagementPageQueryDTO();
        query.setNamePattern(name);
        query.setDocumentStatus(documentStatus);
        query.setLatestVersionStatus(latestVersionStatus);
        query.setOffset(page < 0 || size < 1 ? -1 : (long) page * size);
        query.setLimit(size);
        return managementService.listDocuments(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                query
        );
    }

    @GetMapping("/{documentId}")
    public DocumentManagementVO get(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @PathVariable long documentId
    ) {
        return managementService.getDocument(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                documentId
        );
    }

    @GetMapping("/{documentId}/versions")
    public PageVO<DocumentVersionVO> versions(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long knowledgeBaseId,
            @PathVariable long documentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return managementService.listVersions(
                principal(authentication),
                tenantId,
                knowledgeBaseId,
                documentId,
                page,
                size
        );
    }

    private AdminPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AdminPrincipal value
                ? value : null;
    }
}
