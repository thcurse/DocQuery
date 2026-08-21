package com.doc.docquery.config;

import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.QueryEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.AnswerChatGateway;
import com.doc.docquery.service.impl.AlibabaNavigationEmbeddingAdapter;
import com.doc.docquery.service.impl.AlibabaQueryEmbeddingAdapter;
import com.doc.docquery.service.impl.DeepSeekRetrievalCardChatAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 本地 Stub 验证 LangChain4j 适配器发出的供应商关键参数，不访问真实 API。 */
class RetrievalProviderConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsJsonOutputDisabledThinkingAndBothEmbeddingTextTypes() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setChatApiKey("test-chat-key");
        properties.setEmbeddingApiKey("test-embedding-key");
        properties.setChatBaseUrl(baseUrl());
        properties.setEmbeddingBaseUrl(baseUrl());
        properties.setProviderMaxRetries(0);
        properties.setChatMaxOutputTokens(8192);
        properties.setChatTimeout(Duration.ofSeconds(5));
        properties.setEmbeddingTimeout(Duration.ofSeconds(5));
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        ChatModel chatModel = config.retrievalChatModel(properties);
        AnswerProperties answerProperties = new AnswerProperties();
        answerProperties.setMaxOutputTokens(123);
        ChatModel answerChatModel = config.answerChatModel(properties, answerProperties);
        EmbeddingModel embeddingModel = config.navigationEmbeddingModel(properties);
        EmbeddingModel queryEmbeddingModel = config.queryEmbeddingModel(properties);
        RetrievalCardChatGateway chat = new DeepSeekRetrievalCardChatAdapter(
                chatModel,
                objectMapper
        );
        NavigationEmbeddingGateway embedding = new AlibabaNavigationEmbeddingAdapter(
                embeddingModel
        );
        QueryEmbeddingGateway queryEmbedding = new AlibabaQueryEmbeddingAdapter(
                queryEmbeddingModel
        );
        AnswerChatGateway answerChat = config.answerChatGateway(
                answerChatModel,
                answerProperties
        );

        chat.generateNodes(List.of(new RetrievalCardChatGateway.NodeInput(
                "n1", "Guide > Install", "source"
        )), null);
        List<float[]> vectors = embedding.embedDocuments(List.of("navigation text"));
        float[] queryVector = queryEmbedding.embedQuery("refund condition");
        AnswerChatGateway.Turn answerTurn = answerChat.chat(List.of(
                new AnswerChatGateway.SystemPrompt("controlled answer"),
                new AnswerChatGateway.UserContent("question")
        ), true);
        assertThat(answerTurn.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("searchDocuments");
            assertThat(call.argumentsJson()).contains("refund");
        });

        assertThat(vectors).singleElement().satisfies(vector -> {
            assertThat(vector).hasSize(2560);
            assertThat(vector[0]).isEqualTo(1.0f);
        });
        assertThat(queryVector).hasSize(2560);
        List<CapturedRequest> chatRequests = requests.stream()
                .filter(request -> request.path().endsWith("/chat/completions"))
                .toList();
        assertThat(chatRequests).hasSize(2);
        CapturedRequest chatRequest = chatRequests.get(0);
        JsonNode chatJson = objectMapper.readTree(chatRequest.body());
        assertThat(chatJson.get("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(chatJson.get("response_format").get("type").asText())
                .isEqualTo("json_object");
        assertThat(chatJson.get("thinking").get("type").asText())
                .isEqualTo("disabled");
        assertThat(chatJson.get("max_tokens").asInt()).isEqualTo(8192);
        assertThat(chatRequest.authorization()).isEqualTo("Bearer test-chat-key");
        JsonNode answerJson = objectMapper.readTree(chatRequests.get(1).body());
        assertThat(answerJson.get("max_tokens").asInt()).isEqualTo(123);
        assertThat(answerJson.get("tools")).hasSize(4);
        assertThat(answerJson.get("tools")).extracting(node ->
                        node.get("function").get("name").asText())
                .containsExactly(
                        "searchDocuments",
                        "getDocumentOutline",
                        "searchWithinDocument",
                        "readDocument"
                );
        assertThat(answerJson.get("tools")).allSatisfy(node -> {
            JsonNode function = node.get("function");
            assertThat(function.get("strict")).isNull();
            assertThat(function.get("parameters").get("type").asText())
                    .isEqualTo("object");
            assertThat(function.get("parameters").get("properties").size())
                    .isPositive();
        });
        assertThat(answerJson.get("tool_choice").asText()).isEqualTo("auto");

        List<CapturedRequest> embeddingRequests = requests.stream()
                .filter(request -> request.path().endsWith("/embeddings"))
                .toList();
        assertThat(embeddingRequests).hasSize(2);
        assertThat(embeddingRequests).allSatisfy(request -> {
            JsonNode json = objectMapper.readTree(request.body());
            assertThat(json.get("model").asText()).isEqualTo("qwen3.7-text-embedding");
            assertThat(json.get("dimensions").asInt()).isEqualTo(2560);
            assertThat(json.get("output_type").asText()).isEqualTo("dense");
            assertThat(request.authorization()).isEqualTo("Bearer test-embedding-key");
        });
        assertThat(embeddingRequests.stream()
                .map(request -> objectMapper.readTree(request.body())
                        .get("text_type").asText()))
                .containsExactly("document", "query");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private CapturedRequest requestEndingWith(String suffix) {
        return requests.stream()
                .filter(request -> request.path().endsWith(suffix))
                .findFirst()
                .orElseThrow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body
        ));
        byte[] response = exchange.getRequestURI().getPath().endsWith("/embeddings")
                ? embeddingResponse()
                : chatResponse(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private byte[] chatResponse(String requestBody) {
        if (requestBody.contains("\"tools\"")) {
            return toolCallResponse();
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("items", List.of(Map.of(
                "requestId", "n1",
                "summary", "Install summary",
                "topics", List.of("install"),
                "aliases", List.of(),
                "answerableQuestions", List.of("How to install?")
        )));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "stub-chat");
        response.put("object", "chat.completion");
        response.put("created", 1);
        response.put("model", "deepseek-v4-flash");
        response.put("choices", List.of(Map.of(
                "index", 0,
                "message", Map.of(
                        "role", "assistant",
                        "content", objectMapper.writeValueAsString(content)
                ),
                "finish_reason", "stop"
        )));
        response.put("usage", Map.of(
                "prompt_tokens", 1,
                "completion_tokens", 1,
                "total_tokens", 2
        ));
        return objectMapper.writeValueAsBytes(response);
    }

    private byte[] toolCallResponse() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "searchDocuments");
        function.put("arguments", "{\"query\":\"refund\",\"limit\":1}");
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", "call-1");
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", List.of(toolCall));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "tool_calls");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "stub-answer");
        response.put("object", "chat.completion");
        response.put("created", 1);
        response.put("model", "deepseek-v4-flash");
        response.put("choices", List.of(choice));
        response.put("usage", Map.of(
                "prompt_tokens", 1,
                "completion_tokens", 1,
                "total_tokens", 2
        ));
        return objectMapper.writeValueAsBytes(response);
    }

    private byte[] embeddingResponse() {
        List<Float> vector = new ArrayList<>(2560);
        for (int index = 0; index < 2560; index++) {
            vector.add(index + 1.0f);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", List.of(Map.of(
                "object", "embedding",
                "embedding", vector,
                "index", 0
        )));
        response.put("model", "qwen3.7-text-embedding");
        response.put("usage", Map.of("prompt_tokens", 1, "total_tokens", 1));
        return objectMapper.writeValueAsBytes(response);
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }
}
