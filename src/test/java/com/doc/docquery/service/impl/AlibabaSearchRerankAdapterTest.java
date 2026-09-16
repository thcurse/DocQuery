package com.doc.docquery.service.impl;

import com.doc.docquery.service.SearchRerankGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AlibabaSearchRerankAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsQwen3RerankRequestAndReturnsValidatedScores() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-api/v1/reranks", this::handle);
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/compatible-api/v1/reranks");
        AlibabaSearchRerankAdapter adapter = new AlibabaSearchRerankAdapter(
                objectMapper,
                endpoint,
                "test-rerank-key",
                "qwen3-rerank",
                "Rank by direct support.",
                Duration.ofSeconds(5),
                0
        );

        List<SearchRerankGateway.Score> scores = adapter.rerank(
                "RabbitMQ具体是什么",
                List.of("队列模型说明", "RabbitMQ定义")
        );

        assertThat(scores).containsExactly(
                new SearchRerankGateway.Score(1, 0.91),
                new SearchRerankGateway.Score(0, 0.32)
        );
        CapturedRequest request = captured.get();
        assertThat(request.authorization()).isEqualTo("Bearer test-rerank-key");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("model").asText()).isEqualTo("qwen3-rerank");
        assertThat(body.get("query").asText()).isEqualTo("RabbitMQ具体是什么");
        assertThat(body.get("documents")).extracting(JsonNode::asText)
                .containsExactly("队列模型说明", "RabbitMQ定义");
        assertThat(body.get("top_n").asInt()).isEqualTo(2);
        assertThat(body.get("return_documents").asBoolean()).isFalse();
        assertThat(body.get("instruct").asText()).isEqualTo("Rank by direct support.");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
        captured.set(new CapturedRequest(
                exchange.getRequestHeaders().getFirst("Authorization"),
                requestBody
        ));
        byte[] response = objectMapper.writeValueAsBytes(Map.of(
                "results", List.of(
                        Map.of("index", 1, "relevance_score", 0.91),
                        Map.of("index", 0, "relevance_score", 0.32)
                ),
                "usage", Map.of("total_tokens", 10)
        ));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String authorization, String body) {
    }
}
