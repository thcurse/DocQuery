package com.doc.docquery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页查询条件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageQueryDTO {

    /** 从0开始的页码。 */
    private int page;

    /** 每页条数。 */
    private int size;
}
