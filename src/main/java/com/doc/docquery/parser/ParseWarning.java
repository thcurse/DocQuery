package com.doc.docquery.parser;

/** 不阻止标准化成功、但需要被后续评测或运维感知的解析降级。 */
public record ParseWarning(
        String code,
        String message,
        SourcePosition sourcePosition
) {
}
