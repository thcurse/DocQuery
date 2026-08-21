package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 分页响应。
 */
@Getter
@AllArgsConstructor
public class PageVO<T> {

    private final List<T> items;
    private final int page;
    private final int size;
    private final long total;
}
