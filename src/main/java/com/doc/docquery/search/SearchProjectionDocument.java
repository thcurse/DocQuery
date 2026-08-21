package com.doc.docquery.search;

import java.util.Map;

/** 一条带确定性 Elasticsearch {@code _id} 的投影文档。 */
public record SearchProjectionDocument(String id, Map<String, Object> source) {

    public SearchProjectionDocument {
        source = Map.copyOf(source);
    }
}
