package com.doc.docquery.parser;

import java.util.List;

/** 单个格式 Adapter 的中立输出，尚未包含 canonical range 和标题父子关系。 */
public record ParsedDocument(
        List<ParsedBlock> blocks,
        List<ParseWarning> warnings,
        Integer pageCount
) {

    public ParsedDocument {
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
    }
}
