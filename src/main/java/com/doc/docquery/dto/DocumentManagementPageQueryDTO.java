package com.doc.docquery.dto;

import lombok.Getter;
import lombok.Setter;

/** 管理面文档列表的可信筛选条件。 */
@Getter
@Setter
public class DocumentManagementPageQueryDTO {
    private Long tenantId;
    private Long knowledgeBaseId;
    private String namePattern;
    private String documentStatus;
    private String latestVersionStatus;
    private long offset;
    private int limit;
}
