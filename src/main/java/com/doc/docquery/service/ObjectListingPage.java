package com.doc.docquery.service;

import java.util.List;

/** 有界对象扫描页；令牌由存储供应商生成，null 表示本轮扫描结束。 */
public record ObjectListingPage<T>(List<T> objects, String nextContinuationToken) {

    public ObjectListingPage {
        objects = List.copyOf(objects);
    }
}
