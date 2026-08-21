package com.doc.docquery.parser;

import com.doc.docquery.enums.DocumentSourceFormat;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** CommonMark AST Parser；标题和正文块都保留源码 Source Span。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.parsing.deepdoc",
        name = "enabled",
        havingValue = "false"
)
public class MarkdownDocumentParser implements DocumentFormatParser {

    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    @Override
    public boolean supports(DocumentSourceFormat format) {
        return format == DocumentSourceFormat.MARKDOWN;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        String markdown = StrictUtf8TextReader.read(source.path());
        Node document = parser.parse(markdown);
        List<ParsedBlock> blocks = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        collect(document, blocks, warnings);
        return new ParsedDocument(blocks, warnings, null);
    }

    /** 只对叶子内容块产出记录，容器节点递归下钻，避免列表文本重复。 */
    private void collect(
            Node parent,
            List<ParsedBlock> blocks,
            List<ParseWarning> warnings
    ) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                blocks.add(ParsedBlock.heading(
                        inlineText(heading),
                        position(heading),
                        heading.getLevel(),
                        "MARKDOWN_HEADING"
                ));
                continue;
            }
            if (node instanceof Paragraph paragraph) {
                BlockKind kind = hasAncestor(paragraph, ListItem.class)
                        ? BlockKind.LIST_ITEM
                        : hasAncestor(paragraph, BlockQuote.class)
                        ? BlockKind.QUOTE
                        : BlockKind.PARAGRAPH;
                blocks.add(ParsedBlock.content(
                        kind,
                        inlineText(paragraph),
                        position(paragraph)
                ));
                continue;
            }
            if (node instanceof FencedCodeBlock fenced) {
                blocks.add(ParsedBlock.content(
                        BlockKind.CODE_BLOCK,
                        fenced.getLiteral(),
                        position(fenced)
                ));
                continue;
            }
            if (node instanceof IndentedCodeBlock indented) {
                blocks.add(ParsedBlock.content(
                        BlockKind.CODE_BLOCK,
                        indented.getLiteral(),
                        position(indented)
                ));
                continue;
            }
            if (node instanceof HtmlBlock html) {
                blocks.add(ParsedBlock.content(
                        BlockKind.RAW_TEXT,
                        html.getLiteral(),
                        position(html)
                ));
                warnings.add(new ParseWarning(
                        "STRUCTURE_FLATTENED",
                        "Raw HTML was retained without restoring its structure",
                        position(html)
                ));
                continue;
            }
            collect(node, blocks, warnings);
        }
    }

    private String inlineText(Node parent) {
        StringBuilder text = new StringBuilder();
        appendInline(parent, text);
        return text.toString();
    }

    private void appendInline(Node parent, StringBuilder output) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text value) {
                output.append(value.getLiteral());
            } else if (node instanceof Code code) {
                output.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak) {
                output.append(' ');
            } else if (node instanceof HardLineBreak) {
                output.append('\n');
            } else if (node instanceof HtmlInline html) {
                output.append(html.getLiteral());
            } else {
                appendInline(node, output);
            }
        }
    }

    private SourcePosition position(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            return SourcePosition.markdown(1, 1, 1, 1);
        }
        SourceSpan first = spans.get(0);
        SourceSpan last = spans.get(spans.size() - 1);
        return SourcePosition.markdown(
                first.getLineIndex() + 1,
                first.getColumnIndex() + 1,
                last.getLineIndex() + 1,
                last.getColumnIndex() + last.getLength() + 1
        );
    }

    private boolean hasAncestor(Node node, Class<? extends Node> type) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (type.isInstance(parent)) {
                return true;
            }
        }
        return false;
    }
}
