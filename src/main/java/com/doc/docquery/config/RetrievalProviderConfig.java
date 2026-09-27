package com.doc.docquery.config;

import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.AnswerOutputSchema;
import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.QueryEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.SearchRerankGateway;
import com.doc.docquery.service.impl.AlibabaSearchRerankAdapter;
import com.doc.docquery.service.impl.AlibabaNavigationEmbeddingAdapter;
import com.doc.docquery.service.impl.AlibabaQueryEmbeddingAdapter;
import com.doc.docquery.service.impl.LangChain4jAnswerAgentAdapter;
import com.doc.docquery.service.impl.PackyApiRetrievalCardChatAdapter;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.net.URI;
import java.util.Locale;
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
    ChatModel retrievalChatModel(
            DocumentRetrievalProperties properties,
            ChatProfilesProperties profiles
    ) {
        ChatProfilesProperties.Profile profile = profiles.require(
                properties.getChatProfile()
        );
        return chatModel(
                profile,
                properties.getChatMaxOutputTokens(),
                properties.getChatTimeout(),
                true
        );
    }

    @Bean("answerChatModel")
    ChatModel answerChatModel(
            ChatProfilesProperties profiles,
            AnswerProperties answer
    ) {
        return chatModel(
                profiles.require(answer.getChatProfile()),
                answer.getMaxOutputTokens(),
                answer.getModelTimeout(),
                false
        );
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
        return new PackyApiRetrievalCardChatAdapter(retrievalChatModel, objectMapper);
    }

    AnswerAgentGateway answerAgentGateway(ChatModel model) {
        return new LangChain4jAnswerAgentAdapter(model);
    }

    @Bean
    AnswerAgentGateway answerAgentGateway(
            @Qualifier("answerChatModel") ChatModel answerChatModel,
            ChatProfilesProperties profiles, AnswerProperties answer
    ) {
        ChatProfilesProperties.Profile profile = profiles.require(answer.getChatProfile());
        return new LangChain4jAnswerAgentAdapter(answerChatModel,
                remaining -> streamingModel(profile, answer.getMaxOutputTokens(), minTimeout(remaining, answer.getModelTimeout())),
                remaining -> chatModel(profile, answer.getMaxOutputTokens(), minTimeout(remaining, answer.getModelTimeout()), false),
                AnswerOutputSchema.format(profile), profile.answerIdentity(), profile.getModel() + "/" + profile.normalizedProtocol());
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

    @Bean
    @ConditionalOnProperty(
            prefix = "docquery.query.answer.rerank",
            name = "enabled",
            havingValue = "true"
    )
    SearchRerankGateway searchRerankGateway(
            DocumentRetrievalProperties retrieval,
            AnswerProperties answer,
            ObjectMapper objectMapper
    ) {
        AnswerProperties.Rerank rerank = answer.getRerank();
        String apiKey = StringUtils.hasText(rerank.getApiKey())
                ? rerank.getApiKey()
                : retrieval.getEmbeddingApiKey();
        requireSecret(apiKey, rerank.getModel() + " API key");
        URI endpoint = rerankEndpoint(rerank.getEndpoint(), retrieval.getEmbeddingBaseUrl());
        return new AlibabaSearchRerankAdapter(
                objectMapper,
                endpoint,
                apiKey,
                rerank.getModel(),
                rerank.getInstruct(),
                rerank.getTimeout(),
                rerank.getMaxRetries()
        );
    }

    private ChatModel chatModel(
            ChatProfilesProperties.Profile profile,
            int maxOutputTokens,
            Duration timeout,
            boolean strictRequestJsonSchema
    ) {
        requireSecret(profile.getApiKey(), profile.getModel() + " API key");
        requireSecret(profile.getBaseUrl(), profile.getModel() + " base URL");
        String protocol = requireProtocol(profile.getProtocol());
        JdkHttpClientBuilder http = new JdkHttpClientBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
        if ("RESPONSES".equals(protocol)) {
            OpenAiResponsesChatModel.Builder builder = OpenAiResponsesChatModel.builder()
                    .baseUrl(profile.getBaseUrl())
                    .apiKey(profile.getApiKey())
                    .modelName(profile.getModel())
                    .maxOutputTokens(maxOutputTokens)
                    .reasoningEffort(reasoningEffort(profile))
                    .store(false)
                    .strictJsonSchema(strictRequestJsonSchema)
                    .httpClientBuilder(http)
                    .logRequests(false)
                    .logResponses(false);
            if (!strictRequestJsonSchema) {
                builder.responseFormat(AnswerOutputSchema.format(profile))
                        .strictJsonSchema("JSON_SCHEMA".equals(profile.effectiveFinalOutputMode()));
            }
            return builder.build();
        }
        if ("CHAT_COMPLETIONS".equals(protocol)) {
            return OpenAiChatModel.builder()
                    .baseUrl(profile.getBaseUrl())
                    .apiKey(profile.getApiKey())
                    .modelName(profile.getModel())
                    .responseFormat(AnswerOutputSchema.format(profile))
                    .strictJsonSchema(strictRequestJsonSchema || "JSON_SCHEMA".equals(profile.effectiveFinalOutputMode()))
                    .maxTokens(maxOutputTokens)
                    .maxRetries(strictRequestJsonSchema ? profile.getMaxRetries() : 0)
                    .httpClientBuilder(http)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
        return AnthropicChatModel.builder()
                .baseUrl(profile.getBaseUrl())
                .apiKey(profile.getApiKey())
                .modelName(profile.getModel())
                .maxTokens(maxOutputTokens)
                .maxRetries(strictRequestJsonSchema ? profile.getMaxRetries() : 0)
                .httpClientBuilder(http)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private Duration minTimeout(Duration remaining, Duration configured) {
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private StreamingChatModel streamingModel(ChatProfilesProperties.Profile profile, int tokens, Duration timeout) {
        JdkHttpClientBuilder http = new JdkHttpClientBuilder().connectTimeout(timeout).readTimeout(timeout);
        return switch (requireProtocol(profile.getProtocol())) {
            case "RESPONSES" -> OpenAiResponsesStreamingChatModel.builder()
                    .baseUrl(profile.getBaseUrl()).apiKey(profile.getApiKey()).modelName(profile.getModel())
                    .maxOutputTokens(tokens).reasoningEffort(reasoningEffort(profile))
                    .store(false).responseFormat(AnswerOutputSchema.format(profile))
                    .strictJsonSchema("JSON_SCHEMA".equals(profile.effectiveFinalOutputMode()))
                    .httpClientBuilder(http).logRequests(false).logResponses(false).build();
            case "CHAT_COMPLETIONS" -> OpenAiStreamingChatModel.builder()
                    .baseUrl(profile.getBaseUrl()).apiKey(profile.getApiKey()).modelName(profile.getModel())
                    .maxTokens(tokens).responseFormat(AnswerOutputSchema.format(profile))
                    .strictJsonSchema("JSON_SCHEMA".equals(profile.effectiveFinalOutputMode()))
                    .httpClientBuilder(http).logRequests(false).logResponses(false).build();
            default -> AnthropicStreamingChatModel.builder()
                    .baseUrl(profile.getBaseUrl()).apiKey(profile.getApiKey()).modelName(profile.getModel())
                    .maxTokens(tokens).httpClientBuilder(http).logRequests(false).logResponses(false).build();
        };
    }

    private String reasoningEffort(ChatProfilesProperties.Profile profile) {
        return StringUtils.hasText(profile.getReasoningEffort())
                ? profile.getReasoningEffort().strip().toLowerCase(Locale.ROOT) : null;
    }

    private String requireProtocol(String value) {
        String protocol = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!"RESPONSES".equals(protocol)
                && !"CHAT_COMPLETIONS".equals(protocol)
                && !"ANTHROPIC_MESSAGES".equals(protocol)) {
            throw new IllegalStateException(
                    "Chat protocol must be RESPONSES, CHAT_COMPLETIONS, or ANTHROPIC_MESSAGES"
            );
        }
        return protocol;
    }

    private URI rerankEndpoint(String configured, String embeddingBaseUrl) {
        if (StringUtils.hasText(configured)) {
            return URI.create(configured.strip());
        }
        requireSecret(embeddingBaseUrl, "Alibaba rerank endpoint");
        URI embedding = URI.create(embeddingBaseUrl.strip());
        if (embedding.getScheme() == null || embedding.getHost() == null) {
            throw new IllegalStateException("Alibaba rerank endpoint is invalid");
        }
        return embedding.resolve("/compatible-api/v1/reranks");
    }

    private void requireSecret(String value, String name) {
        if (!StringUtils.hasText(value)) {
            // 只指出缺失项，不回显配置值，避免 Key 或带 Workspace ID 的 URL 泄露。
            throw new IllegalStateException(name + " is required when provider is enabled");
        }
    }
}
