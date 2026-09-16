package com.doc.docquery.config;

import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.QueryEmbeddingGateway;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import com.doc.docquery.service.SearchRerankGateway;
import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.impl.AlibabaNavigationEmbeddingAdapter;
import com.doc.docquery.service.impl.AlibabaQueryEmbeddingAdapter;
import com.doc.docquery.service.impl.PackyApiRetrievalCardChatAdapter;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void sendsPackyCompatibleJsonAndToolsWithoutDeepSeekParameters() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setEmbeddingApiKey("test-embedding-key");
        properties.setEmbeddingBaseUrl(baseUrl());
        properties.setProviderMaxRetries(0);
        properties.setChatMaxOutputTokens(8192);
        properties.setChatTimeout(Duration.ofSeconds(5));
        properties.setEmbeddingTimeout(Duration.ofSeconds(5));
        ChatProfilesProperties profiles = profiles(
                baseUrl(), "RESPONSES", "RESPONSES"
        );
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        ChatModel chatModel = config.retrievalChatModel(properties, profiles);
        AnswerProperties answerProperties = new AnswerProperties();
        answerProperties.setMaxOutputTokens(123);
        ChatModel answerChatModel = config.answerChatModel(profiles, answerProperties);
        EmbeddingModel embeddingModel = config.navigationEmbeddingModel(properties);
        EmbeddingModel queryEmbeddingModel = config.queryEmbeddingModel(properties);
        RetrievalCardChatGateway chat = new PackyApiRetrievalCardChatAdapter(
                chatModel,
                objectMapper
        );
        NavigationEmbeddingGateway embedding = new AlibabaNavigationEmbeddingAdapter(
                embeddingModel
        );
        QueryEmbeddingGateway queryEmbedding = new AlibabaQueryEmbeddingAdapter(
                queryEmbeddingModel
        );
        AnswerAgentGateway answerAgent = config.answerAgentGateway(answerChatModel);

        chat.generateNodes(List.of(new RetrievalCardChatGateway.NodeInput(
                "n1", "Guide > Install", "source"
        )), null);
        List<float[]> vectors = embedding.embedDocuments(List.of("navigation text"));
        float[] queryVector = queryEmbedding.embedQuery("refund condition");
        List<String> executedTools = new ArrayList<>();
        String answer = answerAgent.start(
                new AnswerAgentGateway.Request("controlled answer", 2),
                (name, arguments) -> {
                    executedTools.add(name + ":" + arguments);
                    return "{\"status\":\"OK\",\"candidates\":[]}";
                },
                new NoopObserver()
        ).next("question");
        assertThat(answer).contains("\"evidenceIds\":[]");
        assertThat(executedTools).singleElement().satisfies(call ->
                assertThat(call).startsWith("search:").contains("refund"));

        assertThat(vectors).singleElement().satisfies(vector -> {
            assertThat(vector).hasSize(2560);
            assertThat(vector[0]).isEqualTo(1.0f);
        });
        assertThat(queryVector).hasSize(2560);
        List<CapturedRequest> chatRequests = requests.stream()
                .filter(request -> request.path().endsWith("/responses"))
                .toList();
        assertThat(chatRequests).hasSize(3);
        CapturedRequest chatRequest = chatRequests.get(0);
        JsonNode chatJson = objectMapper.readTree(chatRequest.body());
        assertThat(chatJson.get("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(chatJson.get("text").get("format").get("type").asText())
                .isEqualTo("json_schema");
        JsonNode format = chatJson.get("text").get("format");
        assertThat(format.get("name").asText()).isEqualTo("retrieval_node_cards");
        assertThat(format.get("strict").asBoolean()).isTrue();
        JsonNode cardsSchema = format.get("schema");
        assertThat(cardsSchema.get("properties").propertyNames())
                .containsExactly("items");
        assertThat(cardsSchema.get("required"))
                .extracting(JsonNode::asText).containsExactly("items");
        assertThat(cardsSchema.get("additionalProperties").asBoolean()).isFalse();
        JsonNode cardSchema = cardsSchema.get("properties").get("items").get("items");
        assertThat(cardSchema.get("properties").propertyNames())
                .containsExactly(
                        "requestId",
                        "summary",
                        "topics",
                        "aliases",
                        "answerableQuestions"
                );
        assertThat(cardSchema.get("required"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "requestId",
                        "summary",
                        "topics",
                        "aliases",
                        "answerableQuestions"
                );
        assertThat(cardSchema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(chatJson.get("thinking")).isNull();
        assertThat(chatJson.get("max_output_tokens").asInt()).isEqualTo(8192);
        assertThat(chatJson.get("store").asBoolean()).isFalse();
        assertThat(chatRequest.authorization()).isEqualTo("Bearer test-glm-key");
        JsonNode answerJson = objectMapper.readTree(chatRequests.get(1).body());
        assertThat(answerJson.get("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(chatRequests.get(1).authorization())
                .isEqualTo("Bearer test-sonnet-key");
        assertThat(answerJson.get("max_output_tokens").asInt()).isEqualTo(123);
        assertThat(answerJson.get("tools")).hasSize(3);
        assertThat(answerJson.get("tools")).extracting(node ->
                        node.get("name").asText())
                .containsExactly("search", "open", "submit_evidence");
        assertThat(answerJson.get("tools")).allSatisfy(node -> {
            assertThat(node.path("strict").asBoolean(false)).isFalse();
            assertThat(node.get("parameters").get("type").asText())
                    .isEqualTo("object");
            assertThat(node.get("parameters").get("properties").size())
                    .isPositive();
        });
        JsonNode searchParameters = answerJson.get("tools").get(0)
                .get("parameters");
        assertThat(searchParameters.get("properties").propertyNames())
                .containsExactlyInAnyOrder("query", "documentRef", "cursor");
        assertThat(searchParameters.get("required"))
                .extracting(JsonNode::asText)
                .containsExactly("query");
        JsonNode submitParameters = answerJson.get("tools").get(2)
                .get("parameters");
        assertThat(submitParameters.get("properties").propertyNames())
                .containsExactlyInAnyOrder("evidenceIds", "readRefs");
        assertThat(submitParameters.get("required"))
                .extracting(JsonNode::asText)
                .containsExactly("evidenceIds", "readRefs");
        assertThat(answerJson.get("tool_choice").asText()).isEqualTo("auto");
        JsonNode finalization = objectMapper.readTree(chatRequests.get(2).body());
        assertThat(chatRequests.get(2).body()).contains("function_call_output");
        assertThat(finalization.get("tools")).singleElement().satisfies(node ->
                assertThat(node.get("name").asText()).isEqualTo("submit_evidence"));
        assertThat(finalization.get("tool_choice").asText()).isEqualTo("required");

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

    @Test
    void derivesQwenRerankEndpointFromEmbeddingWorkspaceAndReusesItsKey() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        DocumentRetrievalProperties retrieval = new DocumentRetrievalProperties();
        retrieval.setEmbeddingBaseUrl(baseUrl());
        retrieval.setEmbeddingApiKey("test-embedding-key");
        AnswerProperties answer = new AnswerProperties();
        answer.getRerank().setEnabled(true);
        answer.getRerank().setTimeout(Duration.ofSeconds(5));
        answer.getRerank().setMaxRetries(0);

        SearchRerankGateway reranker = new RetrievalProviderConfig()
                .searchRerankGateway(retrieval, answer, objectMapper);
        List<SearchRerankGateway.Score> scores = reranker.rerank(
                "definition",
                List.of("details", "direct definition")
        );

        assertThat(scores).containsExactly(
                new SearchRerankGateway.Score(1, 0.9),
                new SearchRerankGateway.Score(0, 0.2)
        );
        CapturedRequest request = requestEndingWith("/compatible-api/v1/reranks");
        assertThat(request.authorization()).isEqualTo("Bearer test-embedding-key");
        assertThat(objectMapper.readTree(request.body()).get("model").asText())
                .isEqualTo("qwen3-rerank");
    }

    @Test
    void sendsOpenAiChatCompletionsWhenConfiguredAndKeepsAgentTools() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        ChatProfilesProperties profiles = profiles(
                baseUrl(), "RESPONSES", "CHAT_COMPLETIONS"
        );
        AnswerProperties answerProperties = new AnswerProperties();
        answerProperties.setMaxOutputTokens(321);
        answerProperties.setModelTimeout(Duration.ofSeconds(5));
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        AnswerAgentGateway answerAgent = config.answerAgentGateway(
                config.answerChatModel(profiles, answerProperties)
        );

        List<String> executedTools = new ArrayList<>();
        String answer = answerAgent.start(
                new AnswerAgentGateway.Request("controlled answer", 2),
                (name, arguments) -> {
                    executedTools.add(name + ":" + arguments);
                    return "{\"status\":\"OK\",\"candidates\":[]}";
                },
                new NoopObserver()
        ).next("question");

        assertThat(answer).contains("\"evidenceIds\":[]");
        assertThat(executedTools).singleElement().satisfies(call ->
                assertThat(call).startsWith("search:").contains("refund"));
        List<CapturedRequest> chatRequests = requests.stream()
                .filter(request -> request.path().endsWith("/chat/completions"))
                .toList();
        assertThat(chatRequests).hasSize(2);
        JsonNode first = objectMapper.readTree(chatRequests.get(0).body());
        assertThat(first.get("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(first.get("max_tokens").asInt()).isEqualTo(321);
        assertThat(first.get("response_format").get("type").asText())
                .isEqualTo("json_object");
        assertThat(first.get("tools")).hasSize(3);
        assertThat(first.get("tools")).extracting(node ->
                        node.get("function").get("name").asText())
                .containsExactly("search", "open", "submit_evidence");
        assertThat(first.get("tool_choice").asText()).isEqualTo("auto");
        JsonNode second = objectMapper.readTree(chatRequests.get(1).body());
        assertThat(second.get("messages")).anySatisfy(message -> {
            assertThat(message.path("role").asText()).isEqualTo("tool");
            assertThat(message.path("tool_call_id").asText()).isEqualTo("call-1");
        });
        assertThat(second.get("tools")).singleElement().satisfies(node ->
                assertThat(node.get("function").get("name").asText())
                        .isEqualTo("submit_evidence"));
        assertThat(second.get("tool_choice").asText()).isEqualTo("required");
    }

    @Test
    void sendsAnthropicMessagesWhenConfiguredAndKeepsAgentTools() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        ChatProfilesProperties profiles = profiles(
                baseUrl(), "RESPONSES", "ANTHROPIC_MESSAGES"
        );
        AnswerProperties answerProperties = new AnswerProperties();
        answerProperties.setMaxOutputTokens(456);
        answerProperties.setModelTimeout(Duration.ofSeconds(5));
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        AnswerAgentGateway answerAgent = config.answerAgentGateway(
                config.answerChatModel(profiles, answerProperties)
        );

        List<String> executedTools = new ArrayList<>();
        String answer = answerAgent.start(
                new AnswerAgentGateway.Request("controlled answer", 2),
                (name, arguments) -> {
                    executedTools.add(name + ":" + arguments);
                    return "{\"status\":\"OK\",\"candidates\":[]}";
                },
                new NoopObserver()
        ).next("question");

        assertThat(answer).contains("evidenceIds");
        assertThat(executedTools).singleElement().satisfies(call ->
                assertThat(call).startsWith("search:").contains("refund"));
        List<CapturedRequest> chatRequests = requests.stream()
                .filter(request -> request.path().endsWith("/messages"))
                .toList();
        assertThat(chatRequests).hasSize(2);
        CapturedRequest firstRequest = chatRequests.get(0);
        JsonNode first = objectMapper.readTree(firstRequest.body());
        assertThat(first.get("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(first.get("max_tokens").asInt()).isEqualTo(456);
        assertThat(first.get("output_config")).isNull();
        assertThat(first.get("tools")).hasSize(3);
        assertThat(first.get("tools")).extracting(node -> node.get("name").asText())
                .containsExactly("search", "open", "submit_evidence");
        assertThat(first.get("tool_choice").get("type").asText()).isEqualTo("auto");
        assertThat(firstRequest.authorization()).isNull();
        assertThat(firstRequest.anthropicApiKey()).isEqualTo("test-sonnet-key");
        assertThat(firstRequest.anthropicVersion()).isEqualTo("2023-06-01");
        JsonNode second = objectMapper.readTree(chatRequests.get(1).body());
        assertThat(second.get("messages")).anySatisfy(message ->
                assertThat(message.path("content")).anySatisfy(content -> {
                    assertThat(content.path("type").asText()).isEqualTo("tool_result");
                    assertThat(content.path("tool_use_id").asText()).isEqualTo("toolu-1");
                }));
        assertThat(second.get("tools")).singleElement().satisfies(node ->
                assertThat(node.get("name").asText()).isEqualTo("submit_evidence"));
        assertThat(second.get("tool_choice").get("type").asText()).isEqualTo("any");
    }

    @Test
    void sendsAnthropicStructuredOutputForRetrievalCardsWhenConfigured() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setChatProfile("claude-sonnet-5");
        properties.setChatTimeout(Duration.ofSeconds(5));
        ChatProfilesProperties profiles = profiles(
                baseUrl(), "RESPONSES", "ANTHROPIC_MESSAGES"
        );
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        RetrievalCardChatGateway chat = new PackyApiRetrievalCardChatAdapter(
                config.retrievalChatModel(properties, profiles),
                objectMapper
        );

        chat.generateNodes(List.of(new RetrievalCardChatGateway.NodeInput(
                "n1", "Guide > Install", "source"
        )), null);

        CapturedRequest request = requestEndingWith("/messages");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(body.path("output_config").path("format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema")
                .path("properties").propertyNames()).containsExactly("items");
    }

    @Test
    void rejectsArrayWrappedNodeItemFromCompatibleProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setChatTimeout(Duration.ofSeconds(5));
        ChatProfilesProperties profiles = profiles(
                baseUrl(), "RESPONSES", "ANTHROPIC_MESSAGES"
        );
        RetrievalCardChatGateway chat = new PackyApiRetrievalCardChatAdapter(
                new RetrievalProviderConfig().retrievalChatModel(properties, profiles),
                objectMapper
        );

        assertThatThrownBy(() -> chat.generateNodes(List.of(
                new RetrievalCardChatGateway.NodeInput(
                        "bad-node-array",
                        "Guide > Install",
                        "source"
                )
        ), null))
                .isInstanceOf(RetrievalGenerationException.class)
                .hasMessageContaining("invalid field types; shape=ARRAY");
    }

    @Test
    void ignoresUnknownNodeFieldsFromCompatibleProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        DocumentRetrievalProperties properties = new DocumentRetrievalProperties();
        properties.setChatTimeout(Duration.ofSeconds(5));
        ChatProfilesProperties profiles = profiles(
                baseUrl(), "RESPONSES", "ANTHROPIC_MESSAGES"
        );
        RetrievalCardChatGateway chat = new PackyApiRetrievalCardChatAdapter(
                new RetrievalProviderConfig().retrievalChatModel(properties, profiles),
                objectMapper
        );

        List<RetrievalCardChatGateway.GeneratedNode> generated = chat.generateNodes(
                List.of(new RetrievalCardChatGateway.NodeInput(
                        "node-extra-field",
                        "Guide > Install",
                        "source"
                )),
                null
        );

        assertThat(generated).singleElement().satisfies(node -> {
            assertThat(node.requestId()).isEqualTo("node-extra-field");
            assertThat(node.semantic().summary()).isEqualTo("Install summary");
            assertThat(node.semantic().topics()).containsExactly("install");
            assertThat(node.semantic().aliases()).isEmpty();
            assertThat(node.semantic().answerableQuestions())
                    .containsExactly("How to install?");
        });
    }

    private ChatProfilesProperties profiles(
            String baseUrl,
            String retrievalProtocol,
            String answerProtocol
    ) {
        ChatProfilesProperties.Profile retrieval = new ChatProfilesProperties.Profile();
        retrieval.setBaseUrl(baseUrl);
        retrieval.setApiKey("test-glm-key");
        retrieval.setModel("glm-5.3-flash");
        retrieval.setProtocol(retrievalProtocol);
        retrieval.setMaxRetries(0);
        ChatProfilesProperties.Profile answer = new ChatProfilesProperties.Profile();
        answer.setBaseUrl(baseUrl);
        answer.setApiKey("test-sonnet-key");
        answer.setModel("claude-sonnet-5");
        answer.setProtocol(answerProtocol);
        answer.setMaxRetries(0);
        ChatProfilesProperties profiles = new ChatProfilesProperties();
        profiles.setProfiles(Map.of(
                "glm-5.3-flash", retrieval,
                "claude-sonnet-5", answer
        ));
        return profiles;
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
                exchange.getRequestHeaders().getFirst("x-api-key"),
                exchange.getRequestHeaders().getFirst("anthropic-version"),
                body
        ));
        String path = exchange.getRequestURI().getPath();
        byte[] response = path.endsWith("/embeddings")
                ? embeddingResponse()
                : path.endsWith("/compatible-api/v1/reranks")
                ? rerankResponse()
                : path.endsWith("/messages")
                ? anthropicMessagesResponse(body)
                : path.endsWith("/chat/completions")
                ? chatCompletionsResponse(body)
                : responsesResponse(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private byte[] responsesResponse(String requestBody) {
        if (requestBody.contains("\"tools\"")) {
            if (requestBody.contains("function_call_output")) {
                return toolCallResponse(
                        "fc-submit",
                        "call-submit",
                        "submit_evidence",
                        "{\"evidenceIds\":[]}"
                );
            }
            return toolCallResponse();
        }
        if (requestBody.contains("bad-node-array")) {
            Map<String, Object> malformed = new LinkedHashMap<>();
            malformed.put("items", List.of(List.of(Map.of(
                    "requestId", "bad-node-array",
                    "summary", "Install summary",
                    "topics", List.of("install"),
                    "aliases", List.of(),
                    "answerableQuestions", List.of("How to install?")
            ))));
            return textResponse(
                    "stub-malformed-response",
                    objectMapper.writeValueAsString(malformed)
            );
        }
        if (requestBody.contains("node-extra-field")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requestId", "node-extra-field");
            item.put("titlePath", "Guide > Install");
            item.put("summary", "Install summary");
            item.put("topics", List.of("install"));
            item.put("aliases", List.of());
            item.put("answerableQuestions", List.of("How to install?"));
            return textResponse(
                    "stub-extra-field-response",
                    objectMapper.writeValueAsString(Map.of("items", List.of(item)))
            );
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("items", List.of(Map.of(
                "requestId", "n1",
                "summary", "Install summary",
                "topics", List.of("install"),
                "aliases", List.of(),
                "answerableQuestions", List.of("How to install?")
        )));
        return textResponse(
                "stub-response",
                "```json\n" + objectMapper.writeValueAsString(content) + "\n```"
        );
    }

    private byte[] chatCompletionsResponse(String requestBody) {
        if (requestBody.contains("\"tool_call_id\"")) {
            return chatCompletionToolCall(
                    "call-submit",
                    "submit_evidence",
                    "{\"evidenceIds\":[]}"
            );
        }
        return chatCompletionToolCall("call-1", "search", "{\"query\":\"refund\"}");
    }

    private byte[] chatCompletionToolCall(String id, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", List.of(toolCall));
        return chatCompletionResponse(message, "tool_calls");
    }

    private byte[] anthropicMessagesResponse(String requestBody) {
        if (!requestBody.contains("\"tools\"")) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("items", List.of(Map.of(
                    "requestId", "n1",
                    "summary", "Install summary",
                    "topics", List.of("install"),
                    "aliases", List.of(),
                    "answerableQuestions", List.of("How to install?")
            )));
            return anthropicMessageResponse(
                    List.of(Map.of(
                            "type", "text",
                            "text", objectMapper.writeValueAsString(content)
                    )),
                    "end_turn"
            );
        }
        if (requestBody.contains("\"tool_result\"")) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("evidenceIds", List.of());
            return anthropicMessageResponse(
                    List.of(Map.of(
                            "type", "tool_use",
                            "id", "toolu-submit",
                            "name", "submit_evidence",
                            "input", input
                    )),
                    "tool_use"
            );
        }
        return anthropicMessageResponse(
                List.of(Map.of(
                        "type", "tool_use",
                        "id", "toolu-1",
                        "name", "search",
                        "input", Map.of("query", "refund")
                )),
                "tool_use"
        );
    }

    private byte[] anthropicMessageResponse(List<Map<String, Object>> content, String stopReason) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "msg-stub");
        response.put("type", "message");
        response.put("role", "assistant");
        response.put("model", "claude-sonnet-5");
        response.put("content", content);
        response.put("stop_reason", stopReason);
        response.put("stop_sequence", null);
        response.put("usage", Map.of("input_tokens", 1, "output_tokens", 1));
        return objectMapper.writeValueAsBytes(response);
    }

    private byte[] chatCompletionTextResponse(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text);
        return chatCompletionResponse(message, "stop");
    }

    private byte[] chatCompletionResponse(
            Map<String, Object> message,
            String finishReason
    ) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "chatcmpl-stub");
        response.put("object", "chat.completion");
        response.put("created", 1);
        response.put("model", "claude-sonnet-5");
        response.put("choices", List.of(choice));
        response.put("usage", Map.of(
                "prompt_tokens", 1,
                "completion_tokens", 1,
                "total_tokens", 2
        ));
        return objectMapper.writeValueAsBytes(response);
    }

    private byte[] toolCallResponse() {
        return toolCallResponse(
                "fc-1", "call-1", "search", "{\"query\":\"refund\"}"
        );
    }

    private byte[] toolCallResponse(
            String id,
            String callId,
            String name,
            String arguments
    ) {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", id);
        toolCall.put("type", "function_call");
        toolCall.put("status", "completed");
        toolCall.put("call_id", callId);
        toolCall.put("name", name);
        toolCall.put("arguments", arguments);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "stub-answer");
        response.put("object", "response");
        response.put("created_at", 1);
        response.put("status", "completed");
        response.put("model", "claude-sonnet-5");
        response.put("output", List.of(toolCall));
        response.put("usage", Map.of(
                "input_tokens", 1,
                "output_tokens", 1,
                "total_tokens", 2
        ));
        return objectMapper.writeValueAsBytes(response);
    }

    private byte[] textResponse(String id, String text) {
        Map<String, Object> outputText = new LinkedHashMap<>();
        outputText.put("type", "output_text");
        outputText.put("text", text);
        outputText.put("annotations", List.of());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "msg-1");
        message.put("type", "message");
        message.put("status", "completed");
        message.put("role", "assistant");
        message.put("content", List.of(outputText));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("object", "response");
        response.put("created_at", 1);
        response.put("status", "completed");
        response.put("model", "glm-5.3-flash");
        response.put("output", List.of(message));
        response.put("usage", Map.of(
                "input_tokens", 1,
                "output_tokens", 1,
                "total_tokens", 2
        ));
        return objectMapper.writeValueAsBytes(response);
    }

    private static final class NoopObserver implements AnswerAgentGateway.Observer {
        @Override
        public void beforeModelCall() {
        }

        @Override
        public void toolRound(int requestedCalls) {
        }

        @Override
        public void afterToolCall() {
        }
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

    private byte[] rerankResponse() {
        return objectMapper.writeValueAsBytes(Map.of(
                "results", List.of(
                        Map.of("index", 1, "relevance_score", 0.9),
                        Map.of("index", 0, "relevance_score", 0.2)
                )
        ));
    }

    private record CapturedRequest(
            String path,
            String authorization,
            String anthropicApiKey,
            String anthropicVersion,
            String body
    ) {
    }
}
