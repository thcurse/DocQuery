package com.doc.docquery.parser;

import java.time.Instant;
import java.util.List;

/** 可写入 canonical JSONL v1 的完整内存清单；正文仍按 Block 分条保存。 */
public record CanonicalDocument(
        int schemaVersion,
        long documentVersionId,
        String sourceFormat,
        String sourceSha256,
        String parserName,
        String parserVersion,
        Instant generatedAt,
        List<EvidenceBlock> blocks,
        List<HeadingNode> headings,
        List<ParseWarning> warnings,
        Integer pageCount,
        long textLength,
        String canonicalTextSha256
) {

    public CanonicalDocument {
        blocks = List.copyOf(blocks);
        headings = List.copyOf(headings);
        warnings = List.copyOf(warnings);
    }
}
