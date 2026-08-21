package com.doc.docquery.service;

import com.doc.docquery.audit.AuditedQueryResult;
import com.doc.docquery.dto.AnswerRequestDTO;
import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.vo.AnswerResponseVO;
import com.doc.docquery.vo.RetrieveResponseVO;

/** 正式HTTP服务面唯一允许使用的应用审计门面。 */
public interface AuditedQueryService {
    AuditedQueryResult<RetrieveResponseVO> retrieve(
            String requestId,
            String authorizationHeader,
            String idempotencyKey,
            String callerTraceId,
            String actorRef,
            long knowledgeBaseId,
            RetrieveRequestDTO request
    );

    AuditedQueryResult<AnswerResponseVO> answer(
            String requestId,
            String authorizationHeader,
            String idempotencyKey,
            String callerTraceId,
            String actorRef,
            long knowledgeBaseId,
            AnswerRequestDTO request
    );
}
