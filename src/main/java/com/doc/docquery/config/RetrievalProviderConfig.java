package com.doc.docquery.config;

import com.doc.docquery.service.AnswerChatGateway;
import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.QueryEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.impl.AlibabaNavigationEmbeddingAdapter;
import com.doc.docquery.service.impl.AlibabaQueryEmbeddingAdapter;
import com.doc.docquery.service.impl.DeepSeekAnswerChatAdapter;
import com.doc.docquery.service.impl.DeepSeekRetrievalCardChatAdapter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** 仅在显式开启时构造收费供应商客户端；默认回归不会访问公网。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "docquery.retrieval",
        name = "provider-enabled",
        havingValue = "true"
)
public class RetrievalProviderConfig {

    @Bean
    ChatModel retrievalChatModel(DocumentRetrievalProperties properties) {
        requireSecret(properties.getChatApiKey(), "DeepSeek API key");
        requireSecret(properties.getChatBaseUrl(), "DeepSeek base URL");
        return OpenAiChatModel.builder()
                .baseUrl(properties.getChatBaseUrl())
                .apiKey(properties.getChatApiKey())
                .modelName(properties.getChatModel())
                .responseFormat(dev.langchain4j.model.chat.request.ResponseFormat.JSON)
                .maxTokens(properties.getChatMaxOutputTokens())
                .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                .timeout(properties.getChatTimeout())
                .maxRetries(properties.getProviderMaxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean("answerChatModel")
    ChatModel answerChatModel(
            DocumentRetrievalProperties provider,
            AnswerProperties answer
    ) {
        requireSecret(provider.getChatApiKey(), "DeepSeek API key");
        requireSecret(provider.getChatBaseUrl(), "DeepSeek base URL");
        return OpenAiChatModel.builder()
                .baseUrl(provider.getChatBaseUrl())
                .apiKey(provider.getChatApiKey())
                .modelName(provider.getChatModel())
                .responseFormat(dev.langchain4j.model.chat.request.ResponseFormat.JSON)
                .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                .timeout(answer.getModelTimeout())
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean("navigationEmbeddingModel")
    EmbeddingModel navigationEmbeddingModel(DocumentRetrievalProperties properties) {
        requireSecret(properties.getEmbeddingApiKey(), "Alibaba Model Studio API key");
        requireSecret(properties.getEmbeddingBaseUrl(), "Alibaba embedding base URL");
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbeddingBaseUrl())
                .apiKey(properties.getEmbeddingApiKey())
                .modelName(properties.getEmbeddingModel())
                .dimensions(properties.getEmbeddingDimension())
                .maxSegmentsPerBatch(properties.getEmbeddingBatchSize())
                .encodingFormat("float")
                .customParameters(Map.of(
                        "text_type", "document",
                        "output_type", "dense"
                ))
                .timeout(properties.getEmbeddingTimeout())
                .maxRetries(properties.getProviderMaxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /** 查询向量必须使用 query 类型，不能复用上面的 document 类型 Bean。 */
    @Bean("queryEmbeddingModel")
    EmbeddingModel queryEmbeddingModel(DocumentRetrievalProperties properties) {
        requireSecret(properties.getEmbeddingApiKey(), "Alibaba Model Studio API key");
        requireSecret(properties.getEmbeddingBaseUrl(), "Alibaba embedding base URL");
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getEmbeddingBaseUrl())
                .apiKey(properties.getEmbeddingApiKey())
                .modelName(properties.getEmbeddingModel())
                .dimensions(properties.getEmbeddingDimension())
                .maxSegmentsPerBatch(1)
                .encodingFormat("float")
                .customParameters(Map.of(
                        "text_type", "query",
                        "output_type", "dense"
                ))
                .timeout(properties.getEmbeddingTimeout())
                .maxRetries(properties.getProviderMaxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    RetrievalCardChatGateway retrievalCardChatGateway(
            @Qualifier("retrievalChatModel") ChatModel retrievalChatModel,
            ObjectMapper objectMapper
    ) {
        return new DeepSeekRetrievalCardChatAdapter(retrievalChatModel, objectMapper);
    }

    @Bean
    AnswerChatGateway answerChatGateway(
            @Qualifier("answerChatModel") ChatModel answerChatModel,
            AnswerProperties answerProperties
    ) {
        return new DeepSeekAnswerChatAdapter(
                answerChatModel,
                answerProperties.getMaxOutputTokens()
        );
    }

    @Bean
    NavigationEmbeddingGateway navigationEmbeddingGateway(
            @Qualifier("navigationEmbeddingModel") EmbeddingModel navigationEmbeddingModel
    ) {
        return new AlibabaNavigationEmbeddingAdapter(navigationEmbeddingModel);
    }

    @Bean
    QueryEmbeddingGateway queryEmbeddingGateway(
            @Qualifier("queryEmbeddingModel") EmbeddingModel queryEmbeddingModel
    ) {
        return new AlibabaQueryEmbeddingAdapter(queryEmbeddingModel);
    }

    private void requireSecret(String value, String name) {
        if (!StringUtils.hasText(value)) {
            // 只指出缺失项，不回显配置值，避免 Key 或带 Workspace ID 的 URL 泄露。
            throw new IllegalStateException(name + " is required when provider is enabled");
        }
    }
}
