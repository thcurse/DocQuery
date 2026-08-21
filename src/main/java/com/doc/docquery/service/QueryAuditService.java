package com.doc.docquery.service;

import com.doc.docquery.audit.QueryAuditFailure;
import com.doc.docquery.audit.QueryAuditStart;
import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.entity.ApplicationQueryAuditEntity;

/** 应用查询审计的同步持久化边界。 */
public interface QueryAuditService {
    ApplicationQueryAuditEntity start(QueryAuditStart start);
    void succeed(ApplicationQueryAuditEntity audit, QueryExecutionTelemetry telemetry);
    void fail(
            ApplicationQueryAuditEntity audit,
            QueryExecutionTelemetry telemetry,
            QueryAuditFailure failure
    );
}
