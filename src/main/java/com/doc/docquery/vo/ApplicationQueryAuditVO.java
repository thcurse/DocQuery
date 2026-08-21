package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/** Tenant 管理侧可见的应用查询审计；不包含请求、回答或原文正文。 */
@Getter
@AllArgsConstructor
public class ApplicationQueryAuditVO {
    private final Long id;
    private final String requestId;
    private final Long tenantId;
    private final Long applicationId;
    private final Long credentialId;
    private final String credentialFingerprint;
    private final Long knowledgeBaseId;
    private final String operation;
    private final String callerTraceId;
    private final String actorRef;
    private final String querySha256;
    private final Integer queryCodePoints;
    private final String requestedMode;
    private final String executedMode;
    private final String outcome;
    private final Integer httpStatus;
    private final String failureCategory;
    private final String failureCode;
    private final String queryExecutionId;
    private final String idempotencyDisposition;
    private final String snapshotFingerprint;
    private final Integer activeVersionCount;
    private final boolean degraded;
    private final String degradationReason;
    private final Integer resultCount;
    private final Integer evidenceCount;
    private final String answerStatus;
    private final Integer citationCount;
    private final Integer toolRounds;
    private final Integer toolCalls;
    private final Integer modelCalls;
    private final Integer canonicalCharacters;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime completedAt;
    private final Long durationMs;
}
