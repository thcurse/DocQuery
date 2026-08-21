package com.doc.docquery.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Tenant 管理侧查询应用访问审计的可信筛选条件。 */
@Getter
@Setter
public class QueryAuditPageQueryDTO {
    private Long tenantId;
    private LocalDateTime from;
    private LocalDateTime to;
    private Long applicationId;
    private Long knowledgeBaseId;
    private String operationType;
    private String outcome;
    private String requestId;
    private String queryExecutionId;
    private String callerTraceId;
    private long offset;
    private int limit;
}
