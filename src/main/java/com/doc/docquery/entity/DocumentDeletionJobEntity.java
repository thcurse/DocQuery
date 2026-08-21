package com.doc.docquery.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 整个逻辑文档的一次异步删除尝试事实。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDeletionJobEntity {

    private Long id;
    private Long tenantId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer attemptNo;
    private String status;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String failureCode;
    private String failureMessage;
    private Boolean failureRetryable;
    private String idempotencyKeyHash;
    private String requestFingerprint;
    private Long requestedByAdminId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
