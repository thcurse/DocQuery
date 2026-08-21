package com.doc.docquery.search;

/** ES 查询失败的内部统一分类，不暴露索引名称、查询体或服务端响应。 */
public final class SearchRetrievalException extends RuntimeException {

    public SearchRetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
