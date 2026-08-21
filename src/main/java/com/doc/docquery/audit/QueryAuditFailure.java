package com.doc.docquery.audit;

/** 写入审计终态的安全失败映射。 */
public record QueryAuditFailure(
        QueryAuditOutcome outcome,
        int httpStatus,
        String category,
        String code
) {
}
