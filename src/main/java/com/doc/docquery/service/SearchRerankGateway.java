package com.doc.docquery.service;

import java.util.List;

/** 对 Answer Search 的章节候选进行查询相关性评分，不承担去重职责。 */
public interface SearchRerankGateway {

    List<Score> rerank(String query, List<String> documents);

    record Score(int index, double relevanceScore) {
    }
}
