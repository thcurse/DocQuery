package com.doc.docquery.config;

import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.impl.AlibabaNavigationEmbeddingAdapter;
import com.doc.docquery.service.impl.DeepSeekRetrievalCardChatAdapter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式真实供应商小样本冒烟测试。默认因缺少环境变量跳过，不进入免费回归调用。
 */
class RetrievalProviderSmokeTest {

    @Test
    void realProvidersReturnOneValidCardAndOne2560DimensionVector() {
        String deepSeekKey = System.getenv("DEEPSEEK_API_KEY");
        String dashScopeKey = System.getenv("DASHSCOPE_API_KEY");
        String embeddingBaseUrl = System.getenv("DOCQUERY_ALIBABA_EMBEDDING_BASE_URL");
        Assumptions.assumeTrue("true".equalsIgnoreCase(
                        System.getenv("DOCQUERY_REAL_PROVIDER_SMOKE_ENABLED"))
                        && hasText(deepSeekKey)
                        && hasText(dashScopeKey)
                        && hasText(embeddingBaseUrl),
                "Real provider smoke test requires three explicit environment variables");

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setChatApiKey(deepSeekKey);
        properties.setEmbeddingApiKey(dashScopeKey);
        properties.setEmbeddingBaseUrl(embeddingBaseUrl);
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        ObjectMapper objectMapper = new ObjectMapper();
        RetrievalCardChatGateway chat = new DeepSeekRetrievalCardChatAdapter(
                config.retrievalChatModel(properties),
                objectMapper
        );
        NavigationEmbeddingGateway embedding = new AlibabaNavigationEmbeddingAdapter(
                config.navigationEmbeddingModel(properties)
        );

        List<RetrievalCardChatGateway.GeneratedNode> cards = chat.generateNodes(List.of(
                new RetrievalCardChatGateway.NodeInput(
                        "smoke-1",
                        "Guide > Startup",
                        "Start the service and verify its health endpoint."
                )
        ), null);
        List<float[]> vectors = embedding.embedDocuments(List.of(
                "Document: Guide\nSection: Guide > Startup\nSummary: Start and verify."
        ));

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.requestId()).isEqualTo("smoke-1");
            assertThat(card.semantic().summary()).isNotBlank();
        });
        assertThat(vectors).singleElement().satisfies(vector ->
                assertThat(vector).hasSize(2560));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
