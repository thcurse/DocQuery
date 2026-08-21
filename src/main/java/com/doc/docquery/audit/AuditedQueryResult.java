package com.doc.docquery.audit;

/** Controller 返回业务响应时携带同一次审计的 requestId。 */
public record AuditedQueryResult<T>(String requestId, T body) {
}
