package com.doc.docquery.retrieval;

import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.parser.HeadingNode;
import com.doc.docquery.parser.ParseWarning;
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

/** 严格读取 N2.3 canonical JSONL；不接受缺失 footer 或 footer 后附加记录。 */
@Component
public class CanonicalArtifactReader {

    private final ObjectMapper objectMapper;
    private final CanonicalDocumentValidator validator;

    public CanonicalArtifactReader(
            ObjectMapper objectMapper,
            CanonicalDocumentValidator validator
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public CanonicalDocument read(InputStream input) {
        try {
            return readChecked(input);
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw invalid("Canonical JSONL cannot be decoded", exception);
        }
    }

    private CanonicalDocument readChecked(InputStream input) throws IOException {
        List<EvidenceBlock> blocks = new ArrayList<>();
        List<HeadingNode> headings = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        JsonNode header = null;
        JsonNode footer = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input,
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    throw invalid("Canonical JSONL contains a blank record", null);
                }
                JsonNode record = objectMapper.readTree(line);
                String type = requiredText(record, "recordType");
                if (footer != null) {
                    throw invalid("Canonical JSONL contains records after footer", null);
                }
                switch (type) {
                    case "header" -> {
                        if (header != null || !blocks.isEmpty() || !headings.isEmpty()) {
                            throw invalid("Canonical header is not the first record", null);
                        }
                        header = record;
                    }
                    case "block" -> blocks.add(convertRecord(record, EvidenceBlock.class));
                    case "heading" -> headings.add(convertRecord(record, HeadingNode.class));
                    case "warning" -> warnings.add(convertRecord(record, ParseWarning.class));
                    case "footer" -> footer = record;
                    default -> throw invalid("Canonical JSONL has an unknown record type", null);
                }
            }
        }
        if (header == null || footer == null || !footer.path("complete").asBoolean(false)) {
            throw invalid("Canonical JSONL is incomplete", null);
        }
        requireCount(footer, "blockCount", blocks.size());
        requireCount(footer, "headingCount", headings.size());
        requireCount(footer, "warningCount", warnings.size());

        CanonicalDocument document = new CanonicalDocument(
                requiredInt(header, "schemaVersion"),
                requiredLong(header, "documentVersionId"),
                requiredText(header, "sourceFormat"),
                requiredText(header, "sourceSha256"),
                requiredText(header, "parserName"),
                requiredText(header, "parserVersion"),
                Instant.parse(requiredText(header, "generatedAt")),
                blocks,
                headings,
                warnings,
                nullableInt(footer, "pageCount"),
                requiredLong(footer, "textLength"),
                requiredText(footer, "canonicalTextSha256")
        );
        try {
            validator.validate(document);
        } catch (RuntimeException exception) {
            throw invalid("Canonical JSONL failed structural validation", exception);
        }
        return document;
    }

    private <T> T convertRecord(JsonNode record, Class<T> type) {
        ObjectNode copy = (ObjectNode) record.deepCopy();
        copy.remove("recordType");
        return objectMapper.treeToValue(copy, type);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("Canonical field is missing or invalid", null);
        }
        return value.asText();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("Canonical integer field is missing", null);
        }
        return value.asInt();
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("Canonical long field is missing", null);
        }
        return value.asLong();
    }

    private Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private void requireCount(JsonNode footer, String field, int actual) {
        if (requiredInt(footer, field) != actual) {
            throw invalid("Canonical footer count does not match records", null);
        }
    }

    private RetrievalGenerationException invalid(String message, Throwable cause) {
        // JSON 解析异常可能内嵌原文片段，因此不把底层 cause 暴露给上层日志。
        return new RetrievalGenerationException(
                "CANONICAL_ARTIFACT_INTEGRITY_MISMATCH",
                message,
                false
        );
    }
}
