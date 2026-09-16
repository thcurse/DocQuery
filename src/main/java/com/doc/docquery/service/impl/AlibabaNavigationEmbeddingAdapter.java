package com.doc.docquery.service.impl;

import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 阿里云百炼 OpenAI 兼容 Embedding 适配器。 */
public class AlibabaNavigationEmbeddingAdapter implements NavigationEmbeddingGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AlibabaNavigationEmbeddingAdapter.class
    );

    private final EmbeddingModel embeddingModel;

    public AlibabaNavigationEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        long started = System.nanoTime();
        try {
            List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            TokenUsage usage = response.tokenUsage();
            LOGGER.info(
                    "docquery_provider_usage provider=ALIBABA_MODEL_STUDIO "
                            + "operation=DOCUMENT_EMBEDDING status=SUCCESS elapsedMillis={} "
                            + "itemCount={} inputTokens={} outputTokens={} totalTokens={}",
                    elapsedMillis(started),
                    texts.size(),
                    usage == null ? null : usage.inputTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount()
            );
            List<Embedding> embeddings = response.content();
            return embeddings.stream().map(Embedding::vector).toList();
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "docquery_provider_usage provider=ALIBABA_MODEL_STUDIO "
                            + "operation=DOCUMENT_EMBEDDING status=FAILED elapsedMillis={} "
                            + "itemCount={} failureType={}",
                    elapsedMillis(started),
                    texts.size(),
                    exception.getClass().getSimpleName()
            );
            throw ProviderFailureMapper.embedding(exception);
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
