package com.doc.docquery.search;

/** 删除、计数和验收时必须同时携带的完整多租户版本范围。 */
public record SearchProjectionScope(
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long documentVersionId,
        String projectionFingerprint
) {
}
