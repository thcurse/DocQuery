package com.doc.docquery.service;

import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.dto.AnswerRequestDTO;
import com.doc.docquery.vo.AnswerResponseVO;

/** 正式服务面受控单轮回答能力。 */
public interface AnswerService {

    AnswerResponseVO answer(
            String authorizationHeader,
            String idempotencyKey,
            long knowledgeBaseId,
            AnswerRequestDTO request
    );

    /** N3.4 审计门面使用；既有调用方仍可使用原契约。 */
    default AnswerResponseVO answer(
            String authorizationHeader,
            String idempotencyKey,
            long knowledgeBaseId,
            AnswerRequestDTO request,
            QueryExecutionTelemetry telemetry
    ) {
        return answer(authorizationHeader, idempotencyKey, knowledgeBaseId, request);
    }
}
