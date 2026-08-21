package com.doc.docquery.controller;

import com.doc.docquery.dto.AnswerRequestDTO;
import com.doc.docquery.audit.AuditedQueryResult;
import com.doc.docquery.security.QueryRequestIdFilter;
import com.doc.docquery.service.AuditedQueryService;
import com.doc.docquery.vo.AnswerResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 传统业务后端使用 Application Credential 调用的受控单轮回答接口。 */
@RestController
@RequestMapping("/api/v1/service/knowledge-bases/{knowledgeBaseId}")
public class AnswerController {

    private final AuditedQueryService queryService;

    public AnswerController(AuditedQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping(
            value = "/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AnswerResponseVO> answer(
            @PathVariable long knowledgeBaseId,
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey,
            @RequestHeader(name = "X-DocQuery-Trace-Id", required = false)
            String callerTraceId,
            @RequestHeader(name = "X-DocQuery-Actor-Ref", required = false)
            String actorRef,
            HttpServletRequest httpRequest,
            @RequestBody(required = false) AnswerRequestDTO request
    ) {
        AuditedQueryResult<AnswerResponseVO> result = queryService.answer(
                requestId(httpRequest),
                authorization,
                idempotencyKey,
                callerTraceId,
                actorRef,
                knowledgeBaseId,
                request
        );
        return ResponseEntity.ok()
                .header(QueryRequestIdFilter.HEADER, result.requestId())
                .body(result.body());
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(QueryRequestIdFilter.ATTRIBUTE);
        return value instanceof String requestId ? requestId
                : java.util.UUID.randomUUID().toString();
    }
}
