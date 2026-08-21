package com.doc.docquery.parser;

import com.doc.docquery.enums.DocumentSourceFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 严格 UTF-8 TXT Parser；空行分段且不推断标题。 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.parsing.deepdoc",
        name = "enabled",
        havingValue = "false"
)
public class TextDocumentParser implements DocumentFormatParser {

    @Override
    public boolean supports(DocumentSourceFormat format) {
        return format == DocumentSourceFormat.TXT;
    }

    @Override
    public ParsedDocument parse(ParseSource source) {
        String text = StrictUtf8TextReader.read(source.path());
        String[] lines = text.split("\n", -1);
        List<ParsedBlock> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        int paragraphStartLine = 1;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                flush(blocks, paragraph, paragraphStartLine, index);
                paragraph.clear();
                paragraphStartLine = index + 2;
            } else {
                if (paragraph.isEmpty()) {
                    paragraphStartLine = index + 1;
                }
                paragraph.add(line);
            }
        }
        flush(blocks, paragraph, paragraphStartLine, lines.length);
        return new ParsedDocument(blocks, List.of(), null);
    }

    private void flush(
            List<ParsedBlock> blocks,
            List<String> paragraph,
            int startLine,
            int endLine
    ) {
        if (paragraph.isEmpty()) {
            return;
        }
        blocks.add(ParsedBlock.content(
                BlockKind.PARAGRAPH,
                String.join("\n", paragraph),
                SourcePosition.text(startLine, endLine)
        ));
    }
}
