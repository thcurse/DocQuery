package com.doc.docquery.service.impl;

import com.doc.docquery.service.QueryEmbeddingGateway;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

/** 阿里云百炼 query 类型 Embedding 适配器。 */
public class AlibabaQueryEmbeddingAdapter implements QueryEmbeddingGateway {

    private final EmbeddingModel embeddingModel;

    public AlibabaQueryEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embedQuery(String query) {
        try {
            Embedding embedding = embeddingModel.embed(TextSegment.from(query)).content();
            return embedding.vector();
        } catch (RuntimeException exception) {
            throw ProviderFailureMapper.embedding(exception);
        }
    }
}
