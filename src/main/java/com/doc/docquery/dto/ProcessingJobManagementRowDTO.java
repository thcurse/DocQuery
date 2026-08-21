package com.doc.docquery.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 处理任务及其文档归属的管理查询投影，不直接作为 HTTP 响应。 */
@Getter
@Setter
public class ProcessingJobManagementRowDTO {
    private Long id;
    private Long tenantId;
    private Long knowledgeBaseId;
    private String knowledgeBaseName;
    private Long documentId;
    private String documentName;
    private Long documentVersionId;
    private Integer versionNo;
    private String jobType;
    private Integer attemptNo;
    private String status;
    private String failureCode;
    private String failureMessage;
    private Boolean failureRetryable;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
