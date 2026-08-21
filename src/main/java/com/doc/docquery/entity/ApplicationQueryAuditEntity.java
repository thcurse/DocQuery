package com.doc.docquery.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** application_query_audit 持久化对象；不包含问题、答案或原文正文。 */
@Getter
@Setter
public class ApplicationQueryAuditEntity {
    private Long id;
    private String requestId;
    private Long tenantId;
    private Long applicationId;
    private Long credentialId;
    private String credentialFingerprint;
    private Long knowledgeBaseId;
    private String operationType;
    private String callerTraceId;
    private String actorRef;
    private String querySha256;
    private Integer queryCodePoints;
    private String requestedMode;
    private String executedMode;
    private String outcome;
    private Integer httpStatus;
    private String failureCategory;
    private String failureCode;
    private String queryExecutionId;
    private String idempotencyDisposition;
    private String snapshotFingerprint;
    private Integer activeVersionCount;
    private boolean degraded;
    private String degradationReason;
    private Integer resultCount;
    private Integer evidenceCount;
    private String answerStatus;
    private Integer citationCount;
    private Integer toolRounds;
    private Integer toolCalls;
    private Integer modelCalls;
    private Integer canonicalCharacters;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
