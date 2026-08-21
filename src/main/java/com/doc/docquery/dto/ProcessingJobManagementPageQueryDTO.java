package com.doc.docquery.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 管理面处理任务列表的可信筛选条件。 */
@Getter
@Setter
public class ProcessingJobManagementPageQueryDTO {
    private Long tenantId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long documentVersionId;
    private String status;
    private LocalDateTime from;
    private LocalDateTime to;
    private long offset;
    private int limit;
}
