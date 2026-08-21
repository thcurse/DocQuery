package com.doc.docquery.evaluation;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Protects the frozen MMLongBench-Doc metadata without requiring the ignored PDF corpus. */
class N5PublicEvaluationDatasetTest {

    private static final Path ROOT = Path.of("evaluation", "mmlongbench-docquery-v1");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REVISION = "2ff6aa9237fc777b6627dc57a486e9225ac5fb86";

    @Test
    void frozenMetadataAndPageEvidenceContractStayReproducible() throws Exception {
        assertThat(sha256(ROOT.resolve("manifest.json")))
                .isEqualTo("0423bcaf95044b5399f5f5df5e73ef45e2752e0a961a117be3fa141cb247a579");
        JsonNode manifest = JSON.readTree(ROOT.resolve("manifest.json").toFile());
        assertThat(manifest.path("datasetVersion").asText())
                .isEqualTo("mmlongbench-docquery-v1");
        assertThat(manifest.path("sourceDataset").asText()).isEqualTo("MMLongBench-Doc");
        assertThat(manifest.path("sourceRepository").asText())
                .isEqualTo("yubo2333/MMLongBench-Doc");
        assertThat(manifest.path("sourceRevision").asText()).isEqualTo(REVISION);
        assertThat(manifest.path("sourceParquetSha256").asText())
                .isEqualTo("bcdac3c96669634c34184814cede4fe57cf7ac0f98dde0e85936394f6a56a02d");
        assertThat(manifest.path("sourceRows").asInt()).isEqualTo(1_091);
        assertThat(manifest.path("sourceDocuments").asInt()).isEqualTo(135);
        assertThat(manifest.path("license").asText()).contains("CC-BY-NC-4.0");
        assertThat(manifest.path("officialLeaderboardComparable").asBoolean()).isFalse();
        assertThat(manifest.path("selectionSeed").asText())
                .isEqualTo("docquery-mmlongbench-pure-text-v1");
        assertThat(manifest.path("documentCount").asInt()).isEqualTo(100);
        assertThat(manifest.path("caseSourceDocumentCount").asInt()).isEqualTo(69);
        assertThat(manifest.path("distractorDocumentCount").asInt()).isEqualTo(31);
        assertThat(manifest.path("caseCount").asInt()).isEqualTo(80);
        assertThat(manifest.path("answerableCaseCount").asInt()).isEqualTo(64);
        assertThat(manifest.path("unanswerableCaseCount").asInt()).isEqualTo(16);
        assertThat(manifest.path("sourceAnnotationsSha256").asText())
                .isEqualTo("c099b4ff57bf88cbee199d217eda8c653f44c47e1acb1768da3dcc9efa45b1dd");
        assertThat(manifest.path("casesSha256").asText())
                .isEqualTo("c788014e4a1f66a2675a01193af6e1e2de193728cda23508e87ec7d5647aed21");
        assertThat(manifest.path("corpusChecksumsSha256").asText())
                .isEqualTo("f4be42cdb39511b6e2276e54b6e44ddfc909208829a4a7d469573ff77a74d111");
        assertThat(manifest.path("caseAnswerFormatCounts").path("NONE").asInt())
                .isEqualTo(16);
        assertThat(manifest.path("selection").path("answerableEvidenceSources").get(0).asText())
                .isEqualTo("Pure-text (Plain-text)");

        Map<String, DocumentMetadata> documents = new HashMap<>();
        Map<String, Integer> roleCounts = new HashMap<>();
        Set<String> manifestCaseIds = new HashSet<>();
        for (JsonNode document : manifest.path("documents")) {
            String key = document.path("documentKey").asText();
            String file = document.path("file").asText();
            String sourceId = document.path("sourceDocumentId").asText();
            assertThat(key).startsWith("mmlongbench-");
            assertThat(file).isEqualTo("corpus/" + sourceId);
            assertThat(file).doesNotContain("..");
            assertThat(sourceId).endsWith(".pdf");
            assertThat(document.path("format").asText()).isEqualTo("PDF");
            assertThat(document.path("displayName").asText()).isEqualTo(sourceId);
            assertThat(document.path("sourceUrl").asText())
                    .contains("/resolve/" + REVISION + "/documents/");
            assertThat(document.path("sha256").asText()).matches("[0-9a-f]{64}");
            assertThat(document.path("bytes").asLong()).isPositive();
            String role = document.path("role").asText();
            assertThat(role).isIn("CASE_SOURCE", "DISTRACTOR");
            roleCounts.merge(role, 1, Integer::sum);
            Set<String> caseIds = new HashSet<>();
            for (JsonNode caseId : document.path("caseIds")) {
                assertThat(caseIds.add(caseId.asText())).isTrue();
                assertThat(manifestCaseIds.add(caseId.asText())).isTrue();
            }
            if ("CASE_SOURCE".equals(role)) {
                assertThat(caseIds).isNotEmpty();
            } else {
                assertThat(caseIds).isEmpty();
            }
            assertThat(documents.put(key, new DocumentMetadata(sourceId, file, role, caseIds)))
                    .as("duplicate document key %s", key)
                    .isNull();
        }
        assertThat(documents).hasSize(100);
        assertThat(roleCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "CASE_SOURCE", 69,
                "DISTRACTOR", 31
        ));

        Set<String> caseIds = new HashSet<>();
        Set<Integer> sourceRows = new HashSet<>();
        Map<String, Integer> answerabilityCounts = new HashMap<>();
        Map<String, Integer> domainCounts = new HashMap<>();
        for (String line : Files.readAllLines(ROOT.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode evaluationCase = JSON.readTree(line);
            String caseId = evaluationCase.path("caseId").asText();
            assertThat(caseIds.add(caseId)).as("duplicate case %s", caseId).isTrue();
            assertThat(sourceRows.add(evaluationCase.path("sourceRowIndex").asInt()))
                    .as("duplicate source row of %s", caseId)
                    .isTrue();
            assertThat(evaluationCase.path("split").asText()).isEqualTo("ACCEPTANCE");
            assertThat(evaluationCase.path("sourceDataset").asText())
                    .isEqualTo("MMLongBench-Doc");
            assertThat(evaluationCase.path("question").asText()).isNotBlank();
            String answerability = evaluationCase.path("answerability").asText();
            answerabilityCounts.merge(answerability, 1, Integer::sum);
            domainCounts.merge(evaluationCase.path("sourceDomain").asText(), 1, Integer::sum);

            String documentKey = evaluationCase.path("sourceDocumentKey").asText();
            DocumentMetadata document = documents.get(documentKey);
            assertThat(document).as("document of %s", caseId).isNotNull();
            assertThat(document.sourceId())
                    .isEqualTo(evaluationCase.path("sourceDocumentId").asText());
            assertThat(document.role()).isEqualTo("CASE_SOURCE");
            assertThat(document.caseIds()).contains(caseId);

            if ("ANSWERABLE".equals(answerability)) {
                assertThat(evaluationCase.path("kind").asText()).isEqualTo("PAGE_EVIDENCE");
                assertThat(evaluationCase.path("answerFormat").asText())
                        .isIn("Str", "Int", "Float", "List");
                assertThat(evaluationCase.path("goldAnswers")).hasSize(1);
                assertThat(evaluationCase.path("relevance")).hasSize(1);
                JsonNode relevance = evaluationCase.path("relevance").get(0);
                assertThat(relevance.path("documentKey").asText()).isEqualTo(documentKey);
                assertThat(relevance.path("evidencePages").isEmpty()).isFalse();
                assertThat(relevance.path("evidenceSources").get(0).asText())
                        .isEqualTo("Pure-text (Plain-text)");
            } else {
                assertThat(answerability).isEqualTo("UNANSWERABLE");
                assertThat(evaluationCase.path("kind").asText())
                        .isEqualTo("CONTROLLED_REFUSAL");
                assertThat(evaluationCase.path("questionType").asText()).isEqualTo("NONE");
                assertThat(evaluationCase.path("answerFormat").isNull()).isTrue();
                assertThat(evaluationCase.path("goldAnswers").isEmpty()).isTrue();
                assertThat(evaluationCase.path("relevance").isEmpty()).isTrue();
            }
        }
        assertThat(caseIds).hasSize(80).containsExactlyInAnyOrderElementsOf(manifestCaseIds);
        assertThat(answerabilityCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ANSWERABLE", 64,
                "UNANSWERABLE", 16
        ));
        assertThat(domainCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Academic paper", 10,
                "Administration/Industry file", 14,
                "Brochure", 10,
                "Financial report", 10,
                "Guidebook", 13,
                "Research report / Introduction", 13,
                "Tutorial/Workshop", 10
        ));

        assertThat(nonBlankLineCount(ROOT.resolve("source-annotations.jsonl"))).isEqualTo(1_091);
        assertThat(sha256(ROOT.resolve("source-annotations.jsonl")))
                .isEqualTo(manifest.path("sourceAnnotationsSha256").asText());
        assertThat(sha256(ROOT.resolve("cases.jsonl")))
                .isEqualTo(manifest.path("casesSha256").asText());
        assertThat(sha256(ROOT.resolve("corpus-checksums.sha256")))
                .isEqualTo(manifest.path("corpusChecksumsSha256").asText());

        Map<String, String> metadataChecksums = readChecksums(ROOT.resolve("checksums.sha256"));
        assertThat(metadataChecksums).hasSize(3);
        for (Map.Entry<String, String> checksum : metadataChecksums.entrySet()) {
            assertThat(sha256(ROOT.resolve(checksum.getKey()))).isEqualTo(checksum.getValue());
        }
        Map<String, String> corpusChecksums = readChecksums(
                ROOT.resolve("corpus-checksums.sha256")
        );
        assertThat(corpusChecksums).hasSize(100);
        for (JsonNode document : manifest.path("documents")) {
            assertThat(corpusChecksums)
                    .containsEntry(document.path("file").asText(), document.path("sha256").asText());
        }
    }

    private long nonBlankLineCount(Path path) throws IOException {
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        }
    }

    private Map<String, String> readChecksums(Path path) throws IOException {
        Map<String, String> checksums = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.US_ASCII)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("  ", 2);
            assertThat(parts).hasSize(2);
            assertThat(checksums.put(parts[1], parts[0]))
                    .as("duplicate checksum path %s", parts[1])
                    .isNull();
        }
        return checksums;
    }

    private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record DocumentMetadata(
            String sourceId,
            String file,
            String role,
            Set<String> caseIds
    ) {
    }
}
