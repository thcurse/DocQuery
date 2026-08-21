package com.doc.docquery.service.impl;

import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;

/** 阿里云百炼 OpenAI 兼容 Embedding 适配器。 */
public class AlibabaNavigationEmbeddingAdapter implements NavigationEmbeddingGateway {

    private final EmbeddingModel embeddingModel;

    public AlibabaNavigationEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        try {
            List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            return embeddings.stream().map(Embedding::vector).toList();
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ProviderFailureMapper.embedding(exception);
        }
    }
}
