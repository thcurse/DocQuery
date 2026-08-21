package com.doc.docquery.service;

/** 查询文本向量化端口；N3 固定使用 query 文本类型，不能复用 document Bean。 */
public interface QueryEmbeddingGateway {

    float[] embedQuery(String query);
}
