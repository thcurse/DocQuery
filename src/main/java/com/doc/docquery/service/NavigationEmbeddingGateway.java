package com.doc.docquery.service;

import java.util.List;

/** 隔离导航文本向量化供应商；输入顺序和输出顺序必须一一对应。 */
public interface NavigationEmbeddingGateway {

    List<float[]> embedDocuments(List<String> texts);
}
