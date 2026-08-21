package com.doc.docquery.parser;

/** 已分配稳定 ID、章节归属和全文范围的标准化原文证据块。 */
public record EvidenceBlock(
        String blockId,
        int ordinal,
        BlockKind kind,
        String text,
        long canonicalStart,
        long canonicalEnd,
        String headingNodeId,
        String continuationGroupId,
        SourcePosition sourcePosition
) {
}
