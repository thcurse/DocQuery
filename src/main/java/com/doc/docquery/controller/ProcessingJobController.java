package com.doc.docquery.controller;

import com.doc.docquery.dto.ProcessingJobManagementPageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentManagementService;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.ProcessingJobDetailVO;
import com.doc.docquery.vo.ProcessingJobRetryAcceptedVO;
import com.doc.docquery.vo.ProcessingJobVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** N4.1 ProcessingJob 查询和人工重试接口。 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}/processing-jobs")
public class ProcessingJobController {

    private final DocumentManagementService managementService;

    public ProcessingJobController(DocumentManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping
    public PageVO<ProcessingJobVO> list(
            Authentication authentication,
            @PathVariable long tenantId,
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) Long documentVersionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ProcessingJobManagementPageQueryDTO query = new ProcessingJobManagementPageQueryDTO();
        query.setKnowledgeBaseId(knowledgeBaseId);
        query.setDocumentId(documentId);
        query.setDocumentVersionId(documentVersionId);
        query.setStatus(status);
        query.setFrom(from == null ? null
                : from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        query.setTo(to == null ? null
                : to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        query.setOffset(page < 0 || size < 1 ? -1 : (long) page * size);
        query.setLimit(size);
        return managementService.listProcessingJobs(
                principal(authentication),
                tenantId,
                query
        );
    }

    @GetMapping("/{processingJobId}")
    public ProcessingJobDetailVO get(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long processingJobId
    ) {
        return managementService.getProcessingJob(
                principal(authentication),
                tenantId,
                processingJobId
        );
    }

    @PostMapping("/{processingJobId}/retry")
    public ResponseEntity<ProcessingJobRetryAcceptedVO> retry(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long processingJobId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        ProcessingJobRetryAcceptedVO accepted = managementService.retryProcessingJob(
                principal(authentication),
                tenantId,
                processingJobId,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    private AdminPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AdminPrincipal value
                ? value : null;
    }
}
