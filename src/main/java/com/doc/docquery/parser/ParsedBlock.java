package com.doc.docquery.parser;

/** 格式 Adapter 输出、尚未分配稳定 Block/Heading ID 的源结构块。 */
public record ParsedBlock(
        BlockKind kind,
        String text,
        SourcePosition sourcePosition,
        Integer headingLevel,
        String detectionSource,
        boolean documentTitle
) {

    /** 创建不产生标题节点的普通证据块。 */
    public static ParsedBlock content(
            BlockKind kind,
            String text,
            SourcePosition sourcePosition
    ) {
        return new ParsedBlock(kind, text, sourcePosition, null, null, false);
    }

    /** 创建真实标题候选；级别必须由源结构或确定性样式给出。 */
    public static ParsedBlock heading(
            String text,
            SourcePosition sourcePosition,
            int level,
            String detectionSource
    ) {
        return new ParsedBlock(
                BlockKind.HEADING,
                text,
                sourcePosition,
                level,
                detectionSource,
                false
        );
    }

    /** 创建整份文档的封面标题；它属于根节点而不是章节节点。 */
    public static ParsedBlock title(String text, SourcePosition sourcePosition) {
        return new ParsedBlock(
                BlockKind.TITLE,
                text,
                sourcePosition,
                null,
                null,
                true
        );
    }
}
