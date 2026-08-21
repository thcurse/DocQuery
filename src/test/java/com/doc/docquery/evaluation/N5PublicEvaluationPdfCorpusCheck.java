package com.doc.docquery.evaluation;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.CanonicalDocumentAssembler;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.DocumentFormatParser;
import com.doc.docquery.parser.PdfDocumentParser;
import com.doc.docquery.service.DocumentParseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit N5.1 corpus check. The class name intentionally does not match the default
 * Surefire patterns because the 100 original PDFs are large and gitignored.
 */
class N5PublicEvaluationPdfCorpusCheck {

    private static final Path ROOT = Path.of("evaluation", "mmlongbench-docquery-v1");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void allOriginalPdfsPassProductionParsingAndExposeGoldEvidencePages() throws Exception {
        JsonNode manifest = JSON.readTree(ROOT.resolve("manifest.json").toFile());
        Map<String, Set<Integer>> evidencePages = evidencePagesByDocument();
        DocumentParsingProperties properties = new DocumentParsingProperties();
        PdfDocumentParser parser = new PdfDocumentParser(properties);
        CanonicalDocumentAssembler assembler = new CanonicalDocumentAssembler(properties);
        CanonicalDocumentValidator validator = new CanonicalDocumentValidator();
        List<Failure> failures = new ArrayList<>();
        Map<String, Integer> failureCounts = new TreeMap<>();
        long versionId = 90_000L;
        int parsed = 0;

        for (JsonNode document : manifest.path("documents")) {
            String documentKey = document.path("documentKey").asText();
            String displayName = document.path("displayName").asText();
            Path path = ROOT.resolve(document.path("file").asText()).normalize();
            try {
                if (!path.startsWith(ROOT.resolve("corpus").normalize())) {
                    throw new CheckFailure("INVALID_CORPUS_PATH", path.toString());
                }
                if (!Files.isRegularFile(path)) {
                    throw new CheckFailure("PDF_MISSING", path.toString());
                }
                if (Files.size(path) != document.path("bytes").asLong()) {
                    throw new CheckFailure("PDF_SIZE_MISMATCH", path.toString());
                }
                String digest = sha256(path);
                if (!digest.equals(document.path("sha256").asText())) {
                    throw new CheckFailure("PDF_SHA256_MISMATCH", path.toString());
                }
                CanonicalDocument canonical = assemble(
                        parser,
                        assembler,
                        validator,
                        path,
                        displayName,
                        digest,
                        versionId++
                );
                for (Integer page : evidencePages.getOrDefault(documentKey, Set.of())) {
                    boolean present = canonical.blocks().stream()
                            .anyMatch(block -> block.sourcePosition() != null
                                    && page.equals(block.sourcePosition().pageNumber())
                                    && !block.text().isBlank());
                    if (!present) {
                        throw new CheckFailure(
                                "EVIDENCE_PAGE_HAS_NO_EXTRACTED_TEXT",
                                "page " + page
                        );
                    }
                }
                parsed++;
            } catch (DocumentParseException exception) {
                addFailure(failures, failureCounts, displayName, exception.code(), exception.getMessage());
            } catch (CheckFailure exception) {
                addFailure(failures, failureCounts, displayName, exception.code, exception.getMessage());
            } catch (Exception exception) {
                addFailure(
                        failures,
                        failureCounts,
                        displayName,
                        "UNEXPECTED_" + exception.getClass().getSimpleName(),
                        exception.getMessage()
                );
            }
        }

        System.out.printf(
                "N5.1 PDF corpus check: total=%d parsed=%d failed=%d failureCodes=%s%n",
                manifest.path("documentCount").asInt(), parsed, failures.size(), failureCounts
        );
        failures.forEach(failure -> System.out.printf(
                "N5.1 PDF failure: document=%s code=%s detail=%s%n",
                failure.document(), failure.code(), failure.detail()
        ));
        assertThat(failures)
                .as("production PDF parser failures; see per-document lines above")
                .isEmpty();
        assertThat(parsed).isEqualTo(100);
    }

    private CanonicalDocument assemble(
            PdfDocumentParser parser,
            CanonicalDocumentAssembler assembler,
            CanonicalDocumentValidator validator,
            Path path,
            String displayName,
            String sourceSha256,
            long versionId
    ) {
        DocumentFormatParser.ParseSource source = new DocumentFormatParser.ParseSource(
                path,
                versionId,
                displayName,
                DocumentSourceFormat.PDF,
                sourceSha256
        );
        CanonicalDocument canonical = assembler.assemble(
                versionId,
                displayName,
                DocumentSourceFormat.PDF,
                sourceSha256,
                parser.parse(source),
                Instant.parse("2026-08-16T00:00:00Z")
        );
        validator.validate(canonical);
        return canonical;
    }

    private Map<String, Set<Integer>> evidencePagesByDocument() throws Exception {
        Map<String, Set<Integer>> pages = new HashMap<>();
        for (String line : Files.readAllLines(ROOT.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode evaluationCase = JSON.readTree(line);
            for (JsonNode relevance : evaluationCase.path("relevance")) {
                Set<Integer> documentPages = pages.computeIfAbsent(
                        relevance.path("documentKey").asText(),
                        ignored -> new HashSet<>()
                );
                for (JsonNode page : relevance.path("evidencePages")) {
                    documentPages.add(page.asInt());
                }
            }
        }
        return pages;
    }

    private void addFailure(
            List<Failure> failures,
            Map<String, Integer> counts,
            String document,
            String code,
            String detail
    ) {
        String safeDetail = detail == null ? "" : detail.replaceAll("\\s+", " ").strip();
        failures.add(new Failure(document, code, safeDetail));
        counts.merge(code, 1, Integer::sum);
    }

    private String sha256(Path path) throws Exception {
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

    private record Failure(String document, String code, String detail) {
    }

    private static final class CheckFailure extends RuntimeException {

        private final String code;

        private CheckFailure(String code, String detail) {
            super(detail);
            this.code = code;
        }
    }
}
