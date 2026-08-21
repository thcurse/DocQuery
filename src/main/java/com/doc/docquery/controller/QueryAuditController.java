package com.doc.docquery.controller;

import com.doc.docquery.dto.QueryAuditPageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.QueryAuditManagementService;
import com.doc.docquery.vo.ApplicationQueryAuditVO;
import com.doc.docquery.vo.PageVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Tenant Admin 的应用访问审计只读接口；页面实现属于N4。 */
@RestController
@RequestMapping("/api/admin/v1/tenants/{tenantId}/query-audits")
public class QueryAuditController {

    private final QueryAuditManagementService managementService;

    public QueryAuditController(QueryAuditManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping
    public PageVO<ApplicationQueryAuditVO> list(
            Authentication authentication,
            @PathVariable long tenantId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String queryExecutionId,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        QueryAuditPageQueryDTO query = new QueryAuditPageQueryDTO();
        query.setFrom(from == null ? null : from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        query.setTo(to == null ? null : to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        query.setApplicationId(applicationId);
        query.setKnowledgeBaseId(knowledgeBaseId);
        query.setOperationType(operation);
        query.setOutcome(outcome);
        query.setRequestId(requestId);
        query.setQueryExecutionId(queryExecutionId);
        query.setCallerTraceId(traceId);
        query.setOffset(page < 0 || size < 1 ? -1 : (long) page * size);
        query.setLimit(size);
        return managementService.list(principal(authentication), tenantId, query);
    }

    @GetMapping("/{auditId}")
    public ApplicationQueryAuditVO get(
            Authentication authentication,
            @PathVariable long tenantId,
            @PathVariable long auditId
    ) {
        return managementService.get(principal(authentication), tenantId, auditId);
    }

    private AdminPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AdminPrincipal value
                ? value : null;
    }
}
