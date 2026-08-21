package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.service.DocumentParseException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * PDFBox 文本型单栏 PDF Parser。
 *
 * <p>标题优先使用能够映射到可见文本的 Tagged Structure 和书签，普通 PDF
 * 再使用字号、字重、留白和重复位置组合判断；置信不足时保留正文而不造标题。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.parsing.deepdoc",
        name = "enabled",
        havingValue = "false"
)
public class PdfDocumentParser implements DocumentFormatParser {

    private static final Pattern PAGE_NUMBER = Pattern.compile("^[\\-–— ]*\\d+[\\-–— ]*$");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[。！？.!?;；,:，：]$");

    private final DocumentParsingProperties properties;

    public PdfDocumentParser(DocumentParsingProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(DocumentSourceFormat format) {
        return format == DocumentSourceFormat.PDF;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        validateHeader(source);
        try (PDDocument document = Loader.loadPDF(source.path().toFile())) {
            if (document.getNumberOfPages() > properties.getMaxPdfPages()) {
                throw new DocumentParseException(
                        "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                        "PDF page count exceeds configured limit",
                        false
                );
            }
            if (document.isEncrypted()
                    && !document.getCurrentAccessPermission().canExtractContent()) {
                throw encrypted(null);
            }
            return parseDocument(document);
        } catch (InvalidPasswordException exception) {
            throw encrypted(exception);
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DocumentParseException(
                    "DOCUMENT_CORRUPT",
                    "PDF document could not be parsed",
                    false,
                    exception
            );
        }
    }

    private ParsedDocument parseDocument(PDDocument document) throws IOException {
        List<PageLayout> pages = extractPages(document);
        int totalVisibleLines = pages.stream().mapToInt(page -> page.lines.size()).sum();
        if (totalVisibleLines == 0) {
            throw new DocumentParseException(
                    "PDF_NO_EXTRACTABLE_TEXT",
                    "PDF contains no extractable text",
                    false
            );
        }

        Set<String> repeatedMargins = repeatedMarginLines(pages);
        for (PageLayout page : pages) {
            page.lines.removeIf(line -> isIgnoredMargin(line, page, repeatedMargins));
            long suspiciousRows = page.lines.stream().filter(line -> line.largeGap).count();
            if (suspiciousRows >= 3) {
                throw new DocumentParseException(
                        "PDF_COMPLEX_LAYOUT_UNSUPPORTED",
                        "PDF reading order appears to contain multiple columns",
                        false
                );
            }
        }

        float bodyFontSize = dominantBodyFontSize(pages);
        List<HeadingHint> hints = new ArrayList<>();
        collectOutlineHints(document, hints);
        collectTaggedHints(document, hints);
        applyHints(pages, hints);

        List<Line> visualCandidates = pages.stream()
                .flatMap(page -> page.lines.stream())
                .filter(line -> line.hint == null && isVisibleHeading(line, bodyFontSize, pageOf(pages, line)))
                .toList();
        Set<Float> visualSizes = new TreeSet<>(Comparator.reverseOrder());
        for (Line candidate : visualCandidates) {
            visualSizes.add(roundHalf(candidate.fontSize));
        }

        Line documentTitle = findDocumentTitle(pages, bodyFontSize);
        for (Line line : visualCandidates) {
            if (line == documentTitle) {
                line.documentTitle = true;
                continue;
            }
            line.headingLevel = Math.min(
                    6,
                    new ArrayList<>(visualSizes).indexOf(roundHalf(line.fontSize)) + 1
            );
            line.detectionSource = "PDF_VISIBLE_STYLE";
        }

        List<ParsedBlock> blocks = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        for (HeadingHint hint : hints) {
            if (!hint.resolved && "PDF_OUTLINE".equals(hint.source)) {
                warnings.add(new ParseWarning(
                        "PDF_OUTLINE_UNRESOLVED",
                        "PDF outline item could not be mapped to visible text",
                        null
                ));
            }
        }
        for (PageLayout page : pages) {
            if (page.lines.isEmpty()) {
                warnings.add(new ParseWarning(
                        "EMPTY_SOURCE_PAGE",
                        "PDF page contains no main-body text",
                        SourcePosition.pdf(page.pageNumber, 0, 0, 0)
                ));
                continue;
            }
            emitPage(page, blocks, bodyFontSize);
        }
        if (blocks.isEmpty()) {
            throw new DocumentParseException(
                    "PDF_NO_EXTRACTABLE_TEXT",
                    "PDF contains no main-body text",
                    false
            );
        }
        return new ParsedDocument(blocks, warnings, document.getNumberOfPages());
    }

    /** 将标题单独输出，普通相邻行按页内垂直距离恢复为段落。 */
    private void emitPage(
            PageLayout page,
            List<ParsedBlock> output,
            float bodyFontSize
    ) {
        int pageBlockOrdinal = 0;
        int pageCharacterOffset = 0;
        List<Line> paragraph = new ArrayList<>();
        for (Line line : page.lines) {
            if (line.isHeading() || line.documentTitle) {
                pageCharacterOffset = flushParagraph(
                        output,
                        paragraph,
                        page,
                        pageBlockOrdinal,
                        pageCharacterOffset
                );
                if (!paragraph.isEmpty()) {
                    pageBlockOrdinal++;
                    paragraph.clear();
                }
                SourcePosition position = SourcePosition.pdf(
                        page.pageNumber,
                        pageBlockOrdinal,
                        pageCharacterOffset,
                        pageCharacterOffset + line.text.length()
                );
                if (line.documentTitle) {
                    output.add(ParsedBlock.title(line.text, position));
                } else {
                    output.add(ParsedBlock.heading(
                            line.text,
                            position,
                            line.headingLevel,
                            line.detectionSource
                    ));
                }
                pageCharacterOffset += line.text.length() + 2;
                pageBlockOrdinal++;
                continue;
            }

            if (!paragraph.isEmpty()) {
                Line previous = paragraph.get(paragraph.size() - 1);
                float gap = line.y - previous.y;
                if (gap > Math.max(bodyFontSize * 1.45f, previous.height * 1.6f)) {
                    pageCharacterOffset = flushParagraph(
                            output,
                            paragraph,
                            page,
                            pageBlockOrdinal,
                            pageCharacterOffset
                    );
                    pageBlockOrdinal++;
                    paragraph.clear();
                }
            }
            paragraph.add(line);
        }
        flushParagraph(
                output,
                paragraph,
                page,
                pageBlockOrdinal,
                pageCharacterOffset
        );
    }

    private int flushParagraph(
            List<ParsedBlock> output,
            List<Line> paragraph,
            PageLayout page,
            int pageBlockOrdinal,
            int pageCharacterOffset
    ) {
        if (paragraph.isEmpty()) {
            return pageCharacterOffset;
        }
        StringBuilder text = new StringBuilder();
        for (Line line : paragraph) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(line.text);
        }
        output.add(ParsedBlock.content(
                BlockKind.PARAGRAPH,
                text.toString(),
                SourcePosition.pdf(
                        page.pageNumber,
                        pageBlockOrdinal,
                        pageCharacterOffset,
                        pageCharacterOffset + text.length()
                )
        ));
        return pageCharacterOffset + text.length() + 2;
    }

    private List<PageLayout> extractPages(PDDocument document) throws IOException {
        List<PageLayout> pages = new ArrayList<>();
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            PDPage page = document.getPage(index);
            pages.add(new PageLayout(
                    index + 1,
                    page.getCropBox().getWidth(),
                    page.getCropBox().getHeight()
            ));
        }
        LayoutStripper stripper = new LayoutStripper(pages);
        stripper.getText(document);
        for (PageLayout page : pages) {
            page.buildLines();
        }
        return pages;
    }

    private Set<String> repeatedMarginLines(List<PageLayout> pages) {
        Map<String, Set<Integer>> occurrences = new HashMap<>();
        for (PageLayout page : pages) {
            for (Line line : page.lines) {
                if (line.y <= page.height * 0.12f || line.y >= page.height * 0.88f) {
                    occurrences.computeIfAbsent(normalize(line.text), ignored -> new HashSet<>())
                            .add(page.pageNumber);
                }
            }
        }
        int threshold = Math.max(2, (pages.size() + 1) / 2);
        Set<String> repeated = new HashSet<>();
        occurrences.forEach((text, pageNumbers) -> {
            if (!text.isBlank() && pageNumbers.size() >= threshold) {
                repeated.add(text);
            }
        });
        return repeated;
    }

    private boolean isIgnoredMargin(
            Line line,
            PageLayout page,
            Set<String> repeatedMargins
    ) {
        boolean margin = line.y <= page.height * 0.12f || line.y >= page.height * 0.88f;
        if (margin && repeatedMargins.contains(normalize(line.text))) {
            return true;
        }
        return line.y >= page.height * 0.88f && PAGE_NUMBER.matcher(line.text).matches();
    }

    private float dominantBodyFontSize(List<PageLayout> pages) {
        Map<Float, Integer> weights = new LinkedHashMap<>();
        for (PageLayout page : pages) {
            for (Line line : page.lines) {
                float rounded = roundHalf(line.fontSize);
                weights.merge(rounded, Math.max(1, line.text.length()), Integer::sum);
            }
        }
        return weights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(10f);
    }

    private boolean isVisibleHeading(Line line, float bodyFontSize, PageLayout page) {
        String text = line.text.strip();
        if (text.length() < 2
                || text.length() > 160
                || PAGE_NUMBER.matcher(text).matches()
                || SENTENCE_END.matcher(text).matches()) {
            return false;
        }
        boolean strongerFont = line.fontSize >= bodyFontSize * 1.25f;
        boolean boldHeading = line.bold && line.fontSize >= bodyFontSize * 1.05f;
        if (!strongerFont && !boldHeading) {
            return false;
        }
        int index = page.lines.indexOf(line);
        if (index == 0) {
            return true;
        }
        Line previous = page.lines.get(index - 1);
        return line.y - previous.y >= Math.max(line.height * 1.15f, bodyFontSize * 1.15f)
                || strongerFont;
    }

    private Line findDocumentTitle(List<PageLayout> pages, float bodyFontSize) {
        if (pages.isEmpty()) {
            return null;
        }
        PageLayout firstPage = pages.get(0);
        return firstPage.lines.stream()
                .filter(line -> line.hint == null)
                .filter(line -> line.y < firstPage.height * 0.55f)
                .filter(line -> line.fontSize >= bodyFontSize * 1.55f)
                .filter(line -> line.text.length() <= 200)
                .max(Comparator.comparingDouble(line -> line.fontSize))
                .orElse(null);
    }

    private void collectOutlineHints(PDDocument document, List<HeadingHint> hints) {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return;
        }
        collectOutlineSiblings(document, outline.getFirstChild(), 1, hints);
    }

    private void collectOutlineSiblings(
            PDDocument document,
            PDOutlineItem item,
            int level,
            List<HeadingHint> hints
    ) {
        for (PDOutlineItem current = item;
             current != null;
             current = current.getNextSibling()) {
            try {
                PDPage destination = current.findDestinationPage(document);
                Integer pageNumber = destination == null
                        ? null
                        : document.getPages().indexOf(destination) + 1;
                hints.add(new HeadingHint(
                        current.getTitle(),
                        pageNumber,
                        Math.min(6, level),
                        "PDF_OUTLINE"
                ));
            } catch (IOException ignored) {
                hints.add(new HeadingHint(
                        current.getTitle(),
                        null,
                        Math.min(6, level),
                        "PDF_OUTLINE"
                ));
            }
            if (current.getFirstChild() != null) {
                collectOutlineSiblings(
                        document,
                        current.getFirstChild(),
                        level + 1,
                        hints
                );
            }
        }
    }

    private void collectTaggedHints(PDDocument document, List<HeadingHint> hints) {
        PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
        if (root == null) {
            return;
        }
        List<Object> kids = root.getKids();
        if (kids == null) {
            return;
        }
        for (Object kid : kids) {
            collectTaggedKid(document, kid, hints);
        }
    }

    private void collectTaggedKid(
            PDDocument document,
            Object kid,
            List<HeadingHint> hints
    ) {
        if (!(kid instanceof PDStructureElement element)) {
            return;
        }
        String type = element.getStandardStructureType();
        if (type != null && type.matches("H[1-6]")) {
            String text = firstNonBlank(
                    element.getActualText(),
                    element.getTitle(),
                    element.getAlternateDescription()
            );
            Integer pageNumber = element.getPage() == null
                    ? null
                    : document.getPages().indexOf(element.getPage()) + 1;
            if (text != null) {
                hints.add(new HeadingHint(
                        text,
                        pageNumber,
                        Integer.parseInt(type.substring(1)),
                        "PDF_TAG"
                ));
            }
        }
        List<Object> children = element.getKids();
        if (children == null) {
            return;
        }
        for (Object child : children) {
            collectTaggedKid(document, child, hints);
        }
    }

    /** 结构提示只有与可见行文本一致时才生效。 */
    private void applyHints(List<PageLayout> pages, List<HeadingHint> hints) {
        for (HeadingHint hint : hints) {
            if (hint.text == null || hint.text.isBlank()) {
                continue;
            }
            for (PageLayout page : pages) {
                if (hint.pageNumber != null && hint.pageNumber != page.pageNumber) {
                    continue;
                }
                for (Line line : page.lines) {
                    if (!normalize(line.text).equals(normalize(hint.text))) {
                        continue;
                    }
                    if (line.hint == null || "PDF_TAG".equals(hint.source)) {
                        line.hint = hint;
                        line.headingLevel = hint.level;
                        line.detectionSource = hint.source;
                    }
                    hint.resolved = true;
                    break;
                }
                if (hint.resolved) {
                    break;
                }
            }
        }
    }

    private void validateHeader(ParseSource source) {
        byte[] header = new byte[5];
        try (InputStream input = Files.newInputStream(source.path())) {
            if (input.read(header) != header.length
                    || header[0] != '%'
                    || header[1] != 'P'
                    || header[2] != 'D'
                    || header[3] != 'F'
                    || header[4] != '-') {
                throw new DocumentParseException(
                        "DOCUMENT_FORMAT_MISMATCH",
                        "File is not a PDF document",
                        false
                );
            }
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DocumentParseException(
                    "SOURCE_OBJECT_UNAVAILABLE",
                    "PDF source could not be inspected",
                    true,
                    exception
            );
        }
    }

    private DocumentParseException encrypted(Throwable cause) {
        return new DocumentParseException(
                "ENCRYPTED_DOCUMENT_UNSUPPORTED",
                "Encrypted PDF is unsupported",
                false,
                cause
        );
    }

    private PageLayout pageOf(List<PageLayout> pages, Line line) {
        return pages.get(line.pageNumber - 1);
    }

    private float roundHalf(float value) {
        return Math.round(value * 2f) / 2f;
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /** PDFTextStripper 已完成阅读顺序和重叠文本处理后，收集可见 glyph。 */
    private static final class LayoutStripper extends PDFTextStripper {

        private final List<PageLayout> pages;
        private int currentPage = -1;

        private LayoutStripper(List<PageLayout> pages) throws IOException {
            this.pages = pages;
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(true);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPage++;
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (currentPage < 0 || currentPage >= pages.size()) {
                return;
            }
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                PDFont font = position.getFont();
                pages.get(currentPage).glyphs.add(new Glyph(
                        unicode,
                        position.getXDirAdj(),
                        position.getYDirAdj(),
                        position.getWidthDirAdj(),
                        position.getHeightDir(),
                        position.getFontSizeInPt(),
                        font == null ? "" : font.getName()
                ));
            }
        }
    }

    /** 单页布局只在 Parser 内部使用，不进入持久化模型。 */
    private static final class PageLayout {

        private final int pageNumber;
        private final float width;
        private final float height;
        private final List<Glyph> glyphs = new ArrayList<>();
        private final List<Line> lines = new ArrayList<>();

        private PageLayout(int pageNumber, float width, float height) {
            this.pageNumber = pageNumber;
            this.width = width;
            this.height = height;
        }

        private void buildLines() {
            glyphs.sort(Comparator
                    .comparingDouble((Glyph glyph) -> glyph.y)
                    .thenComparingDouble(glyph -> glyph.x));
            List<List<Glyph>> grouped = new ArrayList<>();
            for (Glyph glyph : glyphs) {
                List<Glyph> line = grouped.isEmpty() ? null : grouped.get(grouped.size() - 1);
                if (line == null
                        || Math.abs(line.get(0).y - glyph.y)
                        > Math.max(2f, glyph.height * 0.4f)) {
                    line = new ArrayList<>();
                    grouped.add(line);
                }
                line.add(glyph);
            }
            for (List<Glyph> lineGlyphs : grouped) {
                lineGlyphs.sort(Comparator.comparingDouble(glyph -> glyph.x));
                StringBuilder text = new StringBuilder();
                float fontSize = 0;
                float lineHeight = 0;
                boolean bold = false;
                boolean largeGap = false;
                float previousEnd = -1;
                for (Glyph glyph : lineGlyphs) {
                    String value = glyph.text;
                    float gap = previousEnd < 0 ? 0 : glyph.x - previousEnd;
                    if (previousEnd >= 0
                            && gap > Math.max(1f, glyph.fontSize * 0.22f)
                            && !endsWithWhitespace(text)
                            && !Character.isWhitespace(value.charAt(0))) {
                        text.append(' ');
                    }
                    if (previousEnd >= 0 && gap > width * 0.18f) {
                        largeGap = true;
                    }
                    text.append(value);
                    previousEnd = Math.max(previousEnd, glyph.x + glyph.width);
                    fontSize = Math.max(fontSize, glyph.fontSize);
                    lineHeight = Math.max(lineHeight, glyph.height);
                    String fontName = glyph.fontName == null
                            ? ""
                            : glyph.fontName.toLowerCase(Locale.ROOT);
                    bold |= fontName.contains("bold")
                            || fontName.contains("black")
                            || fontName.contains("semibold");
                }
                String normalized = text.toString().replaceAll("\\s+", " ").strip();
                if (!normalized.isEmpty()) {
                    lines.add(new Line(
                            pageNumber,
                            normalized,
                            lineGlyphs.get(0).y,
                            fontSize,
                            lineHeight,
                            bold,
                            largeGap
                    ));
                }
            }
        }

        private boolean endsWithWhitespace(StringBuilder text) {
            return !text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1));
        }
    }

    private static final class Line {

        private final int pageNumber;
        private final String text;
        private final float y;
        private final float fontSize;
        private final float height;
        private final boolean bold;
        private final boolean largeGap;
        private HeadingHint hint;
        private Integer headingLevel;
        private String detectionSource;
        private boolean documentTitle;

        private Line(
                int pageNumber,
                String text,
                float y,
                float fontSize,
                float height,
                boolean bold,
                boolean largeGap
        ) {
            this.pageNumber = pageNumber;
            this.text = text;
            this.y = y;
            this.fontSize = fontSize;
            this.height = height;
            this.bold = bold;
            this.largeGap = largeGap;
        }

        private boolean isHeading() {
            return headingLevel != null;
        }
    }

    private static final class HeadingHint {

        private final String text;
        private final Integer pageNumber;
        private final int level;
        private final String source;
        private boolean resolved;

        private HeadingHint(String text, Integer pageNumber, int level, String source) {
            this.text = text;
            this.pageNumber = pageNumber;
            this.level = level;
            this.source = source;
        }
    }

    private record Glyph(
            String text,
            float x,
            float y,
            float width,
            float height,
            float fontSize,
            String fontName
    ) {
    }
}
