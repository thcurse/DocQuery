package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.service.DocumentParseException;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Apache POI DOCX Parser；只读取正文、真实 Heading/outline 和表格文本。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.parsing.deepdoc",
        name = "enabled",
        havingValue = "false"
)
public class DocxDocumentParser implements DocumentFormatParser {

    private static final Pattern HEADING_STYLE = Pattern.compile(
            "(?i)(?:heading|标题)\\s*([1-6])"
    );

    private final DocumentParsingProperties properties;

    public DocxDocumentParser(DocumentParsingProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(DocumentSourceFormat format) {
        return format == DocumentSourceFormat.DOCX;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        validatePackage(source);
        try (InputStream input = Files.newInputStream(source.path());
             XWPFDocument document = new XWPFDocument(input)) {
            List<ParsedBlock> blocks = new ArrayList<>();
            List<ParseWarning> warnings = new ArrayList<>();
            List<IBodyElement> elements = document.getBodyElements();
            boolean flattenedTable = false;
            for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++) {
                IBodyElement element = elements.get(elementIndex);
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    addParagraph(
                            blocks,
                            (XWPFParagraph) element,
                            document,
                            SourcePosition.docxParagraph(elementIndex)
                    );
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    flattenedTable = true;
                    addTable(blocks, (XWPFTable) element, elementIndex);
                }
            }
            if (flattenedTable) {
                warnings.add(new ParseWarning(
                        "STRUCTURE_FLATTENED",
                        "DOCX table text was retained without full table semantics",
                        null
                ));
            }
            return new ParsedDocument(blocks, warnings, null);
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DocumentParseException(
                    "DOCUMENT_CORRUPT",
                    "DOCX document could not be parsed",
                    false,
                    exception
            );
        }
    }

    private void addParagraph(
            List<ParsedBlock> blocks,
            XWPFParagraph paragraph,
            XWPFDocument document,
            SourcePosition position
    ) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        Integer headingLevel = headingLevel(paragraph, document.getStyles());
        if (headingLevel != null) {
            blocks.add(ParsedBlock.heading(
                    text,
                    position,
                    headingLevel,
                    "DOCX_OUTLINE"
            ));
            return;
        }
        if (isTitleStyle(paragraph, document.getStyles())) {
            blocks.add(ParsedBlock.title(text, position));
            return;
        }
        BlockKind kind = paragraph.getNumID() == null
                ? BlockKind.PARAGRAPH
                : BlockKind.LIST_ITEM;
        blocks.add(ParsedBlock.content(kind, text, position));
    }

    private void addTable(
            List<ParsedBlock> blocks,
            XWPFTable table,
            int bodyElementIndex
    ) {
        List<XWPFTableRow> rows = table.getRows();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<XWPFTableCell> cells = rows.get(rowIndex).getTableCells();
            for (int columnIndex = 0; columnIndex < cells.size(); columnIndex++) {
                List<XWPFParagraph> paragraphs = cells.get(columnIndex).getParagraphs();
                for (int paragraphIndex = 0;
                     paragraphIndex < paragraphs.size();
                     paragraphIndex++) {
                    String text = paragraphs.get(paragraphIndex).getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    blocks.add(ParsedBlock.content(
                            BlockKind.TABLE_CELL,
                            text,
                            SourcePosition.docxTable(
                                    bodyElementIndex,
                                    rowIndex,
                                    columnIndex,
                                    paragraphIndex
                            )
                    ));
                }
            }
        }
    }

    /** 优先使用 OOXML outline level，再沿样式继承链检查级别。 */
    private Integer headingLevel(XWPFParagraph paragraph, XWPFStyles styles) {
        Integer direct = outlineLevel(paragraph);
        if (direct != null) {
            return direct;
        }
        String styleId = paragraph.getStyleID();
        Set<String> visited = new HashSet<>();
        for (int depth = 0; styleId != null && depth < 8 && visited.add(styleId); depth++) {
            XWPFStyle style = styles == null ? null : styles.getStyle(styleId);
            if (style != null) {
                Integer inherited = outlineLevel(style);
                if (inherited != null) {
                    return inherited;
                }
                Integer named = namedHeadingLevel(style.getName());
                if (named != null) {
                    return named;
                }
                styleId = style.getBasisStyleID();
            } else {
                Integer named = namedHeadingLevel(styleId);
                if (named != null) {
                    return named;
                }
                break;
            }
        }
        return null;
    }

    private Integer outlineLevel(XWPFParagraph paragraph) {
        if (!paragraph.getCTP().isSetPPr()
                || !paragraph.getCTP().getPPr().isSetOutlineLvl()) {
            return null;
        }
        int zeroBased = paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue();
        return zeroBased >= 0 && zeroBased < 6 ? zeroBased + 1 : null;
    }

    private Integer outlineLevel(XWPFStyle style) {
        if (style.getCTStyle().isSetPPr()
                && style.getCTStyle().getPPr().isSetOutlineLvl()) {
            int zeroBased = style.getCTStyle()
                    .getPPr()
                    .getOutlineLvl()
                    .getVal()
                    .intValue();
            return zeroBased >= 0 && zeroBased < 6 ? zeroBased + 1 : null;
        }
        return null;
    }

    private Integer namedHeadingLevel(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = HEADING_STYLE.matcher(name.replace('_', ' '));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private boolean isTitleStyle(XWPFParagraph paragraph, XWPFStyles styles) {
        String styleId = paragraph.getStyleID();
        if (styleId == null) {
            return false;
        }
        XWPFStyle style = styles == null ? null : styles.getStyle(styleId);
        String name = style == null ? styleId : style.getName();
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.equals("title") || normalized.equals("文档标题");
    }

    /** 在 POI 解析前拒绝格式伪装和明显 ZIP 膨胀。 */
    private void validatePackage(ParseSource source) {
        byte[] magic = new byte[8];
        try (InputStream input = Files.newInputStream(source.path())) {
            int read = input.read(magic);
            if (read >= 8
                    && (magic[0] & 0xff) == 0xD0
                    && (magic[1] & 0xff) == 0xCF
                    && (magic[2] & 0xff) == 0x11
                    && (magic[3] & 0xff) == 0xE0) {
                throw new DocumentParseException(
                        "ENCRYPTED_DOCUMENT_UNSUPPORTED",
                        "Encrypted Office document is unsupported",
                        false
                );
            }
            if (read < 4 || magic[0] != 'P' || magic[1] != 'K') {
                throw new DocumentParseException(
                        "DOCUMENT_FORMAT_MISMATCH",
                        "File is not a DOCX OOXML package",
                        false
                );
            }
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DocumentParseException(
                    "SOURCE_OBJECT_UNAVAILABLE",
                    "DOCX source could not be inspected",
                    true,
                    exception
            );
        }

        long expandedBytes = 0;
        int entryCount = 0;
        boolean contentTypes = false;
        boolean documentXml = false;
        try (ZipFile zip = new ZipFile(source.path().toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > 10_000) {
                    limitExceeded();
                }
                if (entry.getSize() > 0) {
                    expandedBytes += entry.getSize();
                    if (expandedBytes > properties.getMaxDocxExpandedBytes()) {
                        limitExceeded();
                    }
                }
                contentTypes |= "[Content_Types].xml".equals(entry.getName());
                documentXml |= "word/document.xml".equals(entry.getName());
            }
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw new DocumentParseException(
                    "DOCUMENT_CORRUPT",
                    "DOCX ZIP structure is corrupt",
                    false,
                    exception
            );
        } catch (IOException exception) {
            throw new DocumentParseException(
                    "SOURCE_OBJECT_UNAVAILABLE",
                    "DOCX package could not be read",
                    true,
                    exception
            );
        }
        if (!contentTypes || !documentXml) {
            throw new DocumentParseException(
                    "DOCUMENT_FORMAT_MISMATCH",
                    "ZIP package is not a DOCX document",
                    false
            );
        }
    }

    private void limitExceeded() {
        throw new DocumentParseException(
                "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                "DOCX expanded content exceeds configured limit",
                false
        );
    }
}
