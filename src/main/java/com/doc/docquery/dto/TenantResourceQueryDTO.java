package com.doc.docquery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户资源数据库查询条件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantResourceQueryDTO {

    /** 租户ID。 */
    private Long tenantId;

    /** 租户内资源ID。 */
    private Long resourceId;
}
