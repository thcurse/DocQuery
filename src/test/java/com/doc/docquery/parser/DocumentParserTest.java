package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.service.DocumentParseException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** N2.3 四类格式的无容器 Golden Fixture 和失败边界。 */
class DocumentParserTest {

    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentParsingProperties properties = properties();
    private final CanonicalDocumentAssembler assembler = new CanonicalDocumentAssembler(
            properties
    );
    private final CanonicalDocumentValidator validator = new CanonicalDocumentValidator();

    @BeforeEach
    void createWorkspaceTempDirectory() throws IOException {
        Files.createDirectories(Path.of("target"));
        tempDirectory = Files.createTempDirectory(Path.of("target"), "parser-fixture-");
    }

    @AfterEach
    void removeWorkspaceTempDirectory() throws IOException {
        if (tempDirectory == null || !Files.exists(tempDirectory)) {
            return;
        }
        try (var paths = Files.walk(tempDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void markdownAndTextKeepRealStructureAndSourceLines() throws IOException {
        Path markdown = tempDirectory.resolve("manual.md");
        Files.writeString(
                markdown,
                "# Install\n\nRun the service.\n\n## Verify\n\n```text\nhealthy\n```\n",
                StandardCharsets.UTF_8
        );
        CanonicalDocument markdownDocument = assemble(
                new MarkdownDocumentParser(),
                markdown,
                DocumentSourceFormat.MARKDOWN,
                101L
        );

        assertThat(markdownDocument.headings())
                .extracting(HeadingNode::title)
                .containsExactly("Manual", "Install", "Verify");
        assertThat(markdownDocument.blocks())
                .extracting(EvidenceBlock::kind)
                .containsExactly(
                        BlockKind.HEADING,
                        BlockKind.PARAGRAPH,
                        BlockKind.HEADING,
                        BlockKind.CODE_BLOCK
                );
        assertThat(markdownDocument.blocks().get(0).sourcePosition().startLine())
                .isEqualTo(1);
        assertThat(markdownDocument.blocks().get(2).sourcePosition().startLine())
                .isEqualTo(5);
        assertCanonicalText(
                markdownDocument,
                "Install\n\nRun the service.\n\nVerify\n\nhealthy"
        );

        Path levelJump = tempDirectory.resolve("level-jump.md");
        Files.writeString(levelJump, "# Real H1\n\n### Real H3\n\nbody", StandardCharsets.UTF_8);
        CanonicalDocument jumpDocument = assemble(
                new MarkdownDocumentParser(),
                levelJump,
                DocumentSourceFormat.MARKDOWN,
                103L
        );
        assertThat(jumpDocument.headings())
                .extracting(HeadingNode::title)
                .containsExactly("Manual", "Real H1", "Real H3");
        assertThat(jumpDocument.warnings())
                .extracting(ParseWarning::code)
                .contains("HEADING_LEVEL_JUMP");

        Path text = tempDirectory.resolve("notes.txt");
        Files.write(
                text,
                ("\uFEFFfirst line\nsecond line\n\nlast paragraph")
                        .getBytes(StandardCharsets.UTF_8)
        );
        CanonicalDocument textDocument = assemble(
                new TextDocumentParser(),
                text,
                DocumentSourceFormat.TXT,
                102L
        );
        assertThat(textDocument.blocks()).hasSize(2);
        assertThat(textDocument.blocks().get(0).text())
                .isEqualTo("first line\nsecond line");
        assertThat(textDocument.blocks().get(1).sourcePosition().startLine())
                .isEqualTo(4);
        assertThat(textDocument.headings()).hasSize(1);
        assertThat(textDocument.warnings())
                .extracting(ParseWarning::code)
                .contains("NO_REAL_HEADINGS");
        assertCanonicalText(
                textDocument,
                "first line\nsecond line\n\nlast paragraph"
        );
    }

    @Test
    void docxUsesHeadingStylesAndKeepsTableCellPositions() throws IOException {
        Path docx = tempDirectory.resolve("manual.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(docx)) {
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Title");
            title.createRun().setText("Service Manual");
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Startup");
            document.createParagraph().createRun().setText("Start the application.");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("State");
            table.getRow(0).getCell(1).setText("Healthy");
            document.write(output);
        }

        CanonicalDocument canonical = assemble(
                new DocxDocumentParser(properties),
                docx,
                DocumentSourceFormat.DOCX,
                201L
        );

        assertThat(canonical.headings())
                .extracting(HeadingNode::title)
                .containsExactly("Manual", "Startup");
        assertThat(canonical.blocks())
                .extracting(EvidenceBlock::kind)
                .contains(
                        BlockKind.TITLE,
                        BlockKind.HEADING,
                        BlockKind.PARAGRAPH,
                        BlockKind.TABLE_CELL
                );
        EvidenceBlock tableCell = canonical.blocks().stream()
                .filter(block -> block.kind() == BlockKind.TABLE_CELL)
                .findFirst()
                .orElseThrow();
        assertThat(tableCell.sourcePosition().tableRow()).isZero();
        assertThat(canonical.warnings())
                .extracting(ParseWarning::code)
                .contains("STRUCTURE_FLATTENED");
        assertCanonicalText(
                canonical,
                "Service Manual\n\nStartup\n\nStart the application.\n\nState\n\nHealthy"
        );
    }

    @Test
    void pdfFindsVisibleHeadingsWithoutBookmarksAndKeepsPhysicalPage() throws IOException {
        Path pdf = tempDirectory.resolve("manual.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeLine(content, "DocQuery Manual", 20, true, 740);
                writeLine(content, "Installation", 16, true, 650);
                writeLine(content, "Install the service with Docker Desktop.", 11, false, 625);
                writeLine(content, "Verification", 16, true, 550);
                writeLine(content, "The health endpoint returns healthy.", 11, false, 525);
            }
            document.save(pdf.toFile());
        }

        CanonicalDocument canonical = assemble(
                new PdfDocumentParser(properties),
                pdf,
                DocumentSourceFormat.PDF,
                301L
        );

        assertThat(canonical.headings())
                .extracting(HeadingNode::title)
                .containsExactly("Manual", "Installation", "Verification");
        assertThat(canonical.headings().subList(1, 3))
                .extracting(HeadingNode::detectionSource)
                .containsOnly("PDF_VISIBLE_STYLE");
        assertThat(canonical.blocks().get(0).kind()).isEqualTo(BlockKind.TITLE);
        assertThat(canonical.blocks())
                .allSatisfy(block -> assertThat(block.sourcePosition().pageNumber()).isEqualTo(1));
        assertCanonicalText(
                canonical,
                "DocQuery Manual\n\nInstallation\n\nInstall the service with Docker Desktop."
                        + "\n\nVerification\n\nThe health endpoint returns healthy."
        );
    }

    @Test
    void pdfUsesOnlyOutlineAndTaggedHeadingsThatMapToVisibleText() throws IOException {
        Path pdf = tempDirectory.resolve("structured.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeLine(content, "Outline Section", 11, false, 700);
                writeLine(content, "Outline body text.", 11, false, 680);
                writeLine(content, "Tagged Section", 11, false, 620);
                writeLine(content, "Tagged body text.", 11, false, 600);
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            PDOutlineItem outlineItem = new PDOutlineItem();
            outlineItem.setTitle("Outline Section");
            outlineItem.setDestination(page);
            outline.addLast(outlineItem);
            document.getDocumentCatalog().setDocumentOutline(outline);

            PDStructureTreeRoot structureRoot = new PDStructureTreeRoot();
            PDStructureElement tagged = new PDStructureElement("H2", structureRoot);
            tagged.setActualText("Tagged Section");
            tagged.setPage(page);
            structureRoot.appendKid(tagged);
            document.getDocumentCatalog().setStructureTreeRoot(structureRoot);
            document.save(pdf.toFile());
        }

        CanonicalDocument canonical = assemble(
                new PdfDocumentParser(properties),
                pdf,
                DocumentSourceFormat.PDF,
                305L
        );

        assertThat(canonical.headings())
                .extracting(HeadingNode::title)
                .containsExactly("Manual", "Outline Section", "Tagged Section");
        assertThat(canonical.headings().subList(1, 3))
                .extracting(HeadingNode::detectionSource)
                .containsExactly("PDF_OUTLINE", "PDF_TAG");
        assertCanonicalText(
                canonical,
                "Outline Section\n\nOutline body text.\n\nTagged Section\n\nTagged body text."
        );
    }

    @Test
    void titleOnlyPdfProducesRootOnlyAndEncryptedOrImagePdfFails() throws IOException {
        Path titleOnly = tempDirectory.resolve("title-only.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeLine(content, "Annual Report", 20, true, 740);
                writeLine(content, "This report contains a long narrative without sections.", 11, false, 680);
                writeLine(content, "All evidence remains addressable by physical page.", 11, false, 665);
            }
            document.save(titleOnly.toFile());
        }
        CanonicalDocument rootOnly = assemble(
                new PdfDocumentParser(properties),
                titleOnly,
                DocumentSourceFormat.PDF,
                302L
        );
        assertThat(rootOnly.headings()).hasSize(1);
        assertThat(rootOnly.blocks().get(0).kind()).isEqualTo(BlockKind.TITLE);
        assertCanonicalText(
                rootOnly,
                "Annual Report\n\nThis report contains a long narrative without sections."
                        + " All evidence remains addressable by physical page."
        );

        Path imageOnly = tempDirectory.resolve("image-only.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(imageOnly.toFile());
        }
        DocumentParseException noText = catchThrowableOfType(
                DocumentParseException.class,
                () -> new PdfDocumentParser(properties).parse(source(
                        imageOnly,
                        DocumentSourceFormat.PDF,
                        303L
                ))
        );
        assertThat(noText.code()).isEqualTo("PDF_NO_EXTRACTABLE_TEXT");

        Path encrypted = tempDirectory.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-secret",
                    "user-secret",
                    new AccessPermission()
            );
            document.protect(policy);
            document.save(encrypted.toFile());
        }
        DocumentParseException encryptedFailure = catchThrowableOfType(
                DocumentParseException.class,
                () -> new PdfDocumentParser(properties).parse(source(
                        encrypted,
                        DocumentSourceFormat.PDF,
                        304L
                ))
        );
        assertThat(encryptedFailure.code()).isEqualTo("ENCRYPTED_DOCUMENT_UNSUPPORTED");
    }

    @Test
    void invalidCorruptComplexAndResourceLimitedFilesReturnStableFailures() throws IOException {
        Path invalidText = tempDirectory.resolve("invalid.txt");
        Files.write(invalidText, new byte[]{(byte) 0xC3, (byte) 0x28});
        DocumentParseException encoding = catchThrowableOfType(
                DocumentParseException.class,
                () -> new TextDocumentParser().parse(source(
                        invalidText,
                        DocumentSourceFormat.TXT,
                        401L
                ))
        );
        assertThat(encoding.code()).isEqualTo("TEXT_ENCODING_UNSUPPORTED");

        Path fakeDocx = tempDirectory.resolve("fake.docx");
        Files.writeString(fakeDocx, "not a zip", StandardCharsets.UTF_8);
        DocumentParseException mismatch = catchThrowableOfType(
                DocumentParseException.class,
                () -> new DocxDocumentParser(properties).parse(source(
                        fakeDocx,
                        DocumentSourceFormat.DOCX,
                        402L
                ))
        );
        assertThat(mismatch.code()).isEqualTo("DOCUMENT_FORMAT_MISMATCH");

        Path corruptDocx = tempDirectory.resolve("corrupt.docx");
        Files.write(
                corruptDocx,
                new byte[]{0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0}
        );
        DocumentParseException corruptPackage = catchThrowableOfType(
                DocumentParseException.class,
                () -> new DocxDocumentParser(properties).parse(source(
                        corruptDocx,
                        DocumentSourceFormat.DOCX,
                        403L
                ))
        );
        assertThat(corruptPackage.code()).isEqualTo("DOCUMENT_CORRUPT");

        Path corruptPdf = tempDirectory.resolve("corrupt.pdf");
        Files.writeString(corruptPdf, "%PDF-1.7\nnot a PDF body", StandardCharsets.US_ASCII);
        DocumentParseException corruptDocument = catchThrowableOfType(
                DocumentParseException.class,
                () -> new PdfDocumentParser(properties).parse(source(
                        corruptPdf,
                        DocumentSourceFormat.PDF,
                        404L
                ))
        );
        assertThat(corruptDocument.code()).isEqualTo("DOCUMENT_CORRUPT");

        Path tooManyPages = tempDirectory.resolve("too-many-pages.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(tooManyPages.toFile());
        }
        DocumentParsingProperties onePageLimit = properties();
        onePageLimit.setMaxPdfPages(1);
        DocumentParseException limit = catchThrowableOfType(
                DocumentParseException.class,
                () -> new PdfDocumentParser(onePageLimit).parse(source(
                        tooManyPages,
                        DocumentSourceFormat.PDF,
                        405L
                ))
        );
        assertThat(limit.code()).isEqualTo("DOCUMENT_PARSE_LIMIT_EXCEEDED");

        Path multiColumn = tempDirectory.resolve("multi-column.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                for (int row = 0; row < 3; row++) {
                    float y = 700 - row * 30;
                    writeLineAt(content, "left column " + row, 11, false, 72, y);
                    writeLineAt(content, "right column " + row, 11, false, 360, y);
                }
            }
            document.save(multiColumn.toFile());
        }
        DocumentParseException complex = catchThrowableOfType(
                DocumentParseException.class,
                () -> new PdfDocumentParser(properties).parse(source(
                        multiColumn,
                        DocumentSourceFormat.PDF,
                        406L
                ))
        );
        assertThat(complex.code()).isEqualTo("PDF_COMPLEX_LAYOUT_UNSUPPORTED");
    }

    @Test
    void jsonlHasSingleHeaderFooterAndConsistentChecksums() throws IOException {
        Path text = tempDirectory.resolve("canonical.txt");
        Files.writeString(text, "first paragraph\n\nsecond paragraph", StandardCharsets.UTF_8);
        CanonicalDocument canonical = assemble(
                new TextDocumentParser(),
                text,
                DocumentSourceFormat.TXT,
                501L
        );
        CanonicalJsonlWriter writer = new CanonicalJsonlWriter(
                objectMapper,
                properties
        );
        CanonicalJsonlWriter.WrittenArtifact artifact = writer.write(canonical);
        try {
            List<String> lines = Files.readAllLines(artifact.path(), StandardCharsets.UTF_8);
            assertThat(lines.get(0)).contains("\"recordType\":\"header\"");
            assertThat(lines.get(lines.size() - 1))
                    .contains("\"recordType\":\"footer\"")
                    .contains("\"complete\":true");
            assertThat(artifact.sizeBytes()).isEqualTo(Files.size(artifact.path()));
            assertThat(artifact.sha256()).hasSize(64);
            assertThat(lines.stream()
                    .filter(line -> line.contains("\"recordType\":\"block\""))
                    .map(this::readTextField)
                    .toList())
                    .containsExactly("first paragraph", "second paragraph");
            assertCanonicalText(canonical, "first paragraph\n\nsecond paragraph");
        } finally {
            Files.deleteIfExists(artifact.path());
        }
    }

    private CanonicalDocument assemble(
            DocumentFormatParser parser,
            Path path,
            DocumentSourceFormat format,
            long versionId
    ) {
        ParsedDocument parsed = parser.parse(source(path, format, versionId));
        CanonicalDocument canonical = assembler.assemble(
                versionId,
                "Manual",
                format,
                "0".repeat(64),
                parsed,
                Instant.parse("2026-08-05T00:00:00Z")
        );
        validator.validate(canonical);
        return canonical;
    }

    private DocumentFormatParser.ParseSource source(
            Path path,
            DocumentSourceFormat format,
            long versionId
    ) {
        return new DocumentFormatParser.ParseSource(
                path,
                versionId,
                "Manual",
                format,
                "0".repeat(64)
        );
    }

    private void writeLine(
            PDPageContentStream content,
            String text,
            float fontSize,
            boolean bold,
            float y
    ) throws IOException {
        writeLineAt(content, text, fontSize, bold, 72, y);
    }

    private void writeLineAt(
            PDPageContentStream content,
            String text,
            float fontSize,
            boolean bold,
            float x,
            float y
    ) throws IOException {
        content.beginText();
        content.setFont(
                new PDType1Font(
                        bold
                                ? Standard14Fonts.FontName.HELVETICA_BOLD
                                : Standard14Fonts.FontName.HELVETICA
                ),
                fontSize
        );
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private String readTextField(String line) {
        try {
            return objectMapper.readTree(line).get("text").asText();
        } catch (RuntimeException exception) {
            throw new AssertionError("Fixture JSONL must be valid", exception);
        }
    }

    /** 同时核对块拼接、UTF-16 长度和 UTF-8 SHA-256 三种全文表示。 */
    private void assertCanonicalText(CanonicalDocument document, String expected) {
        String rebuilt = document.blocks().stream()
                .map(EvidenceBlock::text)
                .collect(Collectors.joining("\n\n"));
        assertThat(rebuilt).isEqualTo(expected);
        assertThat(document.textLength()).isEqualTo(expected.length());
        assertThat(document.canonicalTextSha256()).isEqualTo(sha256(expected));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 must be available", exception);
        }
    }

    private DocumentParsingProperties properties() {
        DocumentParsingProperties configured = new DocumentParsingProperties();
        configured.setMaxPdfPages(100);
        configured.setMaxBlocks(10_000);
        configured.setMaxBlockChars(65_536);
        configured.setMaxCanonicalBytes(10L * 1024L * 1024L);
        configured.setMaxDocxExpandedBytes(20L * 1024L * 1024L);
        return configured;
    }
}
