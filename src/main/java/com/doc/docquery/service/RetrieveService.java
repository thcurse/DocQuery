package com.doc.docquery.service;

import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.vo.RetrieveResponseVO;

/** 正式服务面带引用检索能力。 */
public interface RetrieveService {

    RetrieveResponseVO retrieve(
            String authorizationHeader,
            String idempotencyKey,
            long knowledgeBaseId,
            RetrieveRequestDTO request
    );

    /** N3.4 审计门面使用；既有调用方仍可使用原契约。 */
    default RetrieveResponseVO retrieve(
            String authorizationHeader,
            String idempotencyKey,
            long knowledgeBaseId,
            RetrieveRequestDTO request,
            QueryExecutionTelemetry telemetry
    ) {
        return retrieve(authorizationHeader, idempotencyKey, knowledgeBaseId, request);
    }
}
