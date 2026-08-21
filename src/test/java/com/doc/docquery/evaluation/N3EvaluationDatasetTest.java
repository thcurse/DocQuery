package com.doc.docquery.evaluation;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.CanonicalDocumentAssembler;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.DocumentFormatParser;
import com.doc.docquery.parser.DocxDocumentParser;
import com.doc.docquery.parser.MarkdownDocumentParser;
import com.doc.docquery.parser.PdfDocumentParser;
import com.doc.docquery.parser.TextDocumentParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents silent drift in the fictional N3 Gold Set and its evidence anchors. */
class N3EvaluationDatasetTest {

    private static final Path ROOT = Path.of("evaluation", "n3-eval-v1");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant GENERATED_AT = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void manifestCasesChecksumsAndAnchorsStayFrozen() throws Exception {
        JsonNode manifest = JSON.readTree(ROOT.resolve("manifest.json").toFile());
        assertThat(manifest.path("datasetVersion").asText()).isEqualTo("n3-eval-v1");
        assertThat(manifest.path("fictional").asBoolean()).isTrue();
        assertThat(manifest.path("documentCount").asInt()).isEqualTo(12);

        Map<String, DocumentFixture> documents = new HashMap<>();
        long versionId = 1_000L;
        for (JsonNode document : manifest.path("documents")) {
            String key = document.path("documentKey").asText();
            Path path = ROOT.resolve(document.path("file").asText());
            assertThat(path).isRegularFile();
            assertThat(Files.size(path)).isEqualTo(document.path("bytes").asLong());
            assertThat(sha256(path)).isEqualTo(document.path("sha256").asText());
            assertThat(documents.put(key, new DocumentFixture(
                    path,
                    document.path("format").asText(),
                    normalize(extractCanonical(
                            path,
                            document.path("format").asText(),
                            document.path("displayName").asText(),
                            document.path("sha256").asText(),
                            versionId++
                    ))
            ))).as("duplicate documentKey %s", key).isNull();
        }
        assertThat(documents).hasSize(12);

        Set<String> caseIds = new HashSet<>();
        Map<String, Integer> splitCounts = new HashMap<>();
        Map<String, Integer> kindCounts = new HashMap<>();
        int caseCount = 0;
        for (String line : Files.readAllLines(ROOT.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode evaluationCase = JSON.readTree(line);
            String caseId = evaluationCase.path("caseId").asText();
            assertThat(caseIds.add(caseId)).as("duplicate caseId %s", caseId).isTrue();
            splitCounts.merge(evaluationCase.path("split").asText(), 1, Integer::sum);
            kindCounts.merge(evaluationCase.path("kind").asText(), 1, Integer::sum);

            boolean answerable = "ANSWERABLE".equals(
                    evaluationCase.path("answerability").asText()
            );
            assertThat(evaluationCase.path("relevance").isEmpty())
                    .as("relevance of %s", caseId)
                    .isEqualTo(!answerable);
            for (JsonNode relevance : evaluationCase.path("relevance")) {
                String documentKey = relevance.path("documentKey").asText();
                DocumentFixture fixture = documents.get(documentKey);
                assertThat(fixture).as("documentKey of %s", caseId).isNotNull();
                assertThat(relevance.path("anchorTexts").isEmpty()).isFalse();
                for (JsonNode anchor : relevance.path("anchorTexts")) {
                    assertThat(fixture.normalizedText())
                            .as("anchor of %s in %s", caseId, fixture.path())
                            .contains(normalize(anchor.asText()));
                }
            }
            caseCount++;
        }

        assertThat(caseCount).isEqualTo(40);
        assertThat(splitCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "DEVELOPMENT", 24,
                "ACCEPTANCE", 16
        ));
        assertThat(kindCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "SINGLE_EVIDENCE", 24,
                "MULTI_EVIDENCE", 8,
                "UNANSWERABLE", 8
        ));
    }

    private String extractCanonical(
            Path path,
            String formatName,
            String documentName,
            String sourceSha256,
            long versionId
    ) {
        DocumentParsingProperties properties = new DocumentParsingProperties();
        DocumentSourceFormat format = DocumentSourceFormat.valueOf(formatName);
        DocumentFormatParser parser = switch (format) {
            case PDF -> new PdfDocumentParser(properties);
            case DOCX -> new DocxDocumentParser(properties);
            case TXT -> new TextDocumentParser();
            case MARKDOWN -> new MarkdownDocumentParser();
        };
        DocumentFormatParser.ParseSource source = new DocumentFormatParser.ParseSource(
                path, versionId, documentName, format, sourceSha256
        );
        CanonicalDocument canonical = new CanonicalDocumentAssembler(properties).assemble(
                versionId,
                documentName,
                format,
                sourceSha256,
                parser.parse(source),
                GENERATED_AT
        );
        new CanonicalDocumentValidator().validate(canonical);
        return canonical.blocks().stream()
                .map(block -> block.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record DocumentFixture(Path path, String format, String normalizedText) {
    }
}
