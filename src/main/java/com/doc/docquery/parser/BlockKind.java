package com.doc.docquery.parser;

/** 标准化原文块类型；它表达源结构，不表达检索或模型语义。 */
public enum BlockKind {
    TITLE,
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE_CELL,
    QUOTE,
    CODE_BLOCK,
    RAW_TEXT
}
