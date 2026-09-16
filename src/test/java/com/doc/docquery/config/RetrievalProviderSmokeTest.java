package com.doc.docquery.config;

import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import com.doc.docquery.service.impl.AlibabaNavigationEmbeddingAdapter;
import com.doc.docquery.service.impl.PackyApiRetrievalCardChatAdapter;
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
        String packyApiKey = System.getenv("DOCQUERY_GLM_5_3_FLASH_API_KEY");
        String dashScopeKey = System.getenv("DASHSCOPE_API_KEY");
        String embeddingBaseUrl = System.getenv("DOCQUERY_ALIBABA_EMBEDDING_BASE_URL");
        Assumptions.assumeTrue("true".equalsIgnoreCase(
                        System.getenv("DOCQUERY_REAL_PROVIDER_SMOKE_ENABLED"))
                        && hasText(packyApiKey)
                        && hasText(dashScopeKey)
                        && hasText(embeddingBaseUrl),
                "Real provider smoke test requires three explicit environment variables");

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setEmbeddingApiKey(dashScopeKey);
        properties.setEmbeddingBaseUrl(embeddingBaseUrl);
        ChatProfilesProperties profiles = new ChatProfilesProperties();
        ChatProfilesProperties.Profile profile = new ChatProfilesProperties.Profile();
        profile.setModel("glm-5.3-flash");
        profile.setProtocol("RESPONSES");
        profile.setApiKey(packyApiKey);
        String chatBaseUrl = System.getenv("DOCQUERY_GLM_5_3_FLASH_BASE_URL");
        profile.setBaseUrl(hasText(chatBaseUrl)
                ? chatBaseUrl : "https://slb-v1.api.fan/v1");
        profiles.setProfiles(java.util.Map.of("glm-5.3-flash", profile));
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        ObjectMapper objectMapper = new ObjectMapper();
        RetrievalCardChatGateway chat = new PackyApiRetrievalCardChatAdapter(
                config.retrievalChatModel(properties, profiles),
                objectMapper
        );
        NavigationEmbeddingGateway embedding = new AlibabaNavigationEmbeddingAdapter(
                config.navigationEmbeddingModel(properties)
        );

        List<RetrievalCardChatGateway.NodeInput> inputs = List.of(
                new RetrievalCardChatGateway.NodeInput(
                        "smoke-1",
                        "Guide > Startup",
                        "Start the service and verify its health endpoint."
                )
        );
        List<RetrievalCardChatGateway.GeneratedNode> cards;
        try {
            cards = chat.generateNodes(inputs, null);
        } catch (RetrievalGenerationException exception) {
            if (!"RETRIEVAL_MODEL_OUTPUT_INVALID".equals(exception.code())) {
                throw exception;
            }
            cards = chat.generateNodes(
                    inputs,
                    "Previous output failed schema validation; return exactly the "
                            + "required JSON object with all fields and correct types."
            );
        }
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
