package com.doc.docquery.retrieval;

import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 读回并完整验证 retrieval.jsonl，而不是只相信对象大小和 ETag。 */
@Component
public class RetrievalJsonlReader {

    private final ObjectMapper objectMapper;
    private final RetrievalArtifactValidator validator;

    public RetrievalJsonlReader(
            ObjectMapper objectMapper,
            RetrievalArtifactValidator validator
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public ReadArtifact read(InputStream input) {
        try {
            return readChecked(input);
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw invalid("Retrieval JSONL cannot be decoded", exception);
        }
    }

    private ReadArtifact readChecked(InputStream input) throws IOException {
        JsonNode header = null;
        JsonNode footer = null;
        DocumentProfile profile = null;
        List<RetrievalNode> nodes = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input,
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || footer != null) {
                    throw invalid("Retrieval JSONL order is invalid", null);
                }
                JsonNode record = objectMapper.readTree(line);
                String type = requiredText(record, "recordType");
                switch (type) {
                    case "header" -> {
                        if (header != null || profile != null) {
                            throw invalid("Retrieval header is not first", null);
                        }
                        header = record;
                    }
                    case "profile" -> {
                        if (header == null || profile != null || !nodes.isEmpty()) {
                            throw invalid("Retrieval profile order is invalid", null);
                        }
                        profile = convertRecord(record, DocumentProfile.class);
                    }
                    case "node" -> {
                        if (profile == null) {
                            throw invalid("Retrieval node precedes profile", null);
                        }
                        nodes.add(convertRecord(record, RetrievalNode.class));
                    }
                    case "footer" -> footer = record;
                    default -> throw invalid("Retrieval JSONL has an unknown record type", null);
                }
            }
        }
        if (header == null || profile == null || footer == null
                || !footer.path("complete").asBoolean(false)
                || requiredInt(footer, "profileCount") != 1
                || requiredInt(footer, "nodeCount") != nodes.size()
                || requiredInt(footer, "vectorCount") != nodes.size() + 1) {
            throw invalid("Retrieval JSONL is incomplete", null);
        }
        RetrievalArtifact artifact = new RetrievalArtifact(
                requiredInt(header, "schemaVersion"),
                requiredLong(header, "documentVersionId"),
                requiredText(header, "canonicalArtifactSha256"),
                requiredText(header, "chatProvider"),
                requiredText(header, "chatProtocol"),
                requiredText(header, "chatModel"),
                requiredText(header, "thinkingMode"),
                requiredText(header, "promptVersion"),
                requiredText(header, "embeddingModel"),
                requiredInt(header, "embeddingDimension"),
                requiredText(header, "embeddingTemplateVersion"),
                requiredText(header, "generationFingerprint"),
                Instant.parse(requiredText(header, "generatedAt")),
                profile,
                nodes
        );
        if (!supportedChatMetadata(artifact.chatProvider(), artifact.thinkingMode())
                || !"ALIBABA_MODEL_STUDIO".equals(
                requiredText(header, "embeddingProvider"))) {
            throw invalid("Retrieval provider metadata is invalid", null);
        }
        RetrievalArtifactValidator.ValidationResult validation = validator.validate(artifact);
        if (!validation.semanticSha256().equals(requiredText(footer, "semanticSha256"))
                || !validation.vectorSha256().equals(requiredText(footer, "vectorSha256"))
                || !artifact.generationFingerprint().equals(
                requiredText(footer, "generationFingerprint"))) {
            throw invalid("Retrieval footer digest does not match records", null);
        }
        return new ReadArtifact(artifact, validation);
    }

    private boolean supportedChatMetadata(String provider, String thinkingMode) {
        return ("DEEPSEEK".equals(provider) && "DISABLED".equals(thinkingMode))
                || ("PACKY_API".equals(provider)
                && "PROVIDER_DEFAULT".equals(thinkingMode));
    }

    private <T> T convertRecord(JsonNode record, Class<T> type) {
        ObjectNode copy = (ObjectNode) record.deepCopy();
        copy.remove("recordType");
        return objectMapper.treeToValue(copy, type);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("Retrieval text field is missing", null);
        }
        return value.asText();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("Retrieval integer field is missing", null);
        }
        return value.asInt();
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("Retrieval long field is missing", null);
        }
        return value.asLong();
    }

    private RetrievalGenerationException invalid(String message, Throwable cause) {
        // JSON 解析异常可能携带语义正文或向量片段，稳定异常不保留该 cause。
        return new RetrievalGenerationException(
                "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                message,
                false
        );
    }

    public record ReadArtifact(
            RetrievalArtifact artifact,
            RetrievalArtifactValidator.ValidationResult validation
    ) {
    }
}
