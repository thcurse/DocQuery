package com.doc.docquery.service.impl;

import com.doc.docquery.service.SearchRerankGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 阿里云百炼 qwen3-rerank 兼容接口适配器。 */
public class AlibabaSearchRerankAdapter implements SearchRerankGateway {

    private static final Logger LOG = LoggerFactory.getLogger(
            AlibabaSearchRerankAdapter.class
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String instruct;
    private final Duration timeout;
    private final int maxRetries;

    public AlibabaSearchRerankAdapter(
            ObjectMapper objectMapper,
            URI endpoint,
            String apiKey,
            String model,
            String instruct,
            Duration timeout,
            int maxRetries
    ) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.instruct = instruct;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public List<Score> rerank(String query, List<String> documents) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Rerank query and documents are required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);
        if (instruct != null && !instruct.isBlank()) {
            body.put("instruct", instruct.strip());
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)
                ))
                .build();

        long started = System.nanoTime();
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    List<Score> scores = decode(response.body(), documents.size());
                    LOG.info(
                            "docquery_answer_rerank provider=ALIBABA_MODEL_STUDIO "
                                    + "model={} status=SUCCESS candidateCount={} "
                                    + "elapsedMillis={}",
                            model,
                            documents.size(),
                            elapsedMillis(started)
                    );
                    return scores;
                }
                lastFailure = new IllegalStateException(
                        "Rerank provider returned HTTP " + response.statusCode()
                );
                if (response.statusCode() < 500 && response.statusCode() != 429) {
                    break;
                }
            } catch (IOException exception) {
                lastFailure = new IllegalStateException("Rerank request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Rerank request was interrupted", exception);
            }
        }
        LOG.warn(
                "docquery_answer_rerank provider=ALIBABA_MODEL_STUDIO model={} "
                        + "status=FAILED candidateCount={} elapsedMillis={} exceptionType={}",
                model,
                documents.size(),
                elapsedMillis(started),
                lastFailure == null ? "UNKNOWN" : lastFailure.getClass().getSimpleName()
        );
        throw lastFailure == null
                ? new IllegalStateException("Rerank request failed")
                : lastFailure;
    }

    private List<Score> decode(String body, int documentCount) {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() != documentCount) {
            throw new IllegalStateException("Rerank response result count is invalid");
        }
        List<Score> scores = new ArrayList<>(documentCount);
        Set<Integer> indexes = new HashSet<>();
        for (JsonNode item : results) {
            JsonNode indexNode = item.get("index");
            JsonNode scoreNode = item.get("relevance_score");
            if (indexNode == null || !indexNode.canConvertToInt()
                    || scoreNode == null || !scoreNode.isNumber()) {
                throw new IllegalStateException("Rerank response item is invalid");
            }
            int index = indexNode.asInt();
            double score = scoreNode.asDouble();
            if (index < 0 || index >= documentCount || !Double.isFinite(score)
                    || !indexes.add(index)) {
                throw new IllegalStateException("Rerank response index or score is invalid");
            }
            scores.add(new Score(index, score));
        }
        return List.copyOf(scores);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
