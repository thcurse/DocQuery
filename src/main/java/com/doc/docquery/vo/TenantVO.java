package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 租户响应。
 */
@Getter
@AllArgsConstructor
public class TenantVO {

    private final Long id;
    private final String name;
    private final String status;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
