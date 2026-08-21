package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 知识库响应。
 */
@Getter
@AllArgsConstructor
public class KnowledgeBaseVO {

    private final Long id;
    private final Long tenantId;
    private final String name;
    private final String description;
    private final String status;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
