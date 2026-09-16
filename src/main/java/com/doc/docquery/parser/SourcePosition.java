package com.doc.docquery.parser;

/**
 * 四种格式共用的可空位置联合体。
 *
 * <p>PDF 页码、Markdown/TXT 行列为方便引用使用 1-based；DOCX 正文元素和
 * 表格索引使用 0-based，字段语义会原样写入 canonical JSONL。</p>
 */
public record SourcePosition(
        String sourceType,
        Integer pageNumber,
        Integer pageBlockOrdinal,
        Integer pageCharacterStart,
        Integer pageCharacterEnd,
        Integer bodyElementIndex,
        Integer tableRow,
        Integer tableColumn,
        Integer cellParagraphIndex,
        Integer startLine,
        Integer startColumn,
        Integer endLine,
        Integer endColumn,
        String tableId,
        Integer tableRowSpan,
        Integer tableColumnSpan,
        String tableColumnHeader,
        String tableCaption,
        String tableRowHeader,
        String tableCellHeading,
        String tableGroupId,
        String tableContinuationOf
) {

    /** PDF 物理页内的位置。 */
    public static SourcePosition pdf(
            int pageNumber,
            int pageBlockOrdinal,
            int pageCharacterStart,
            int pageCharacterEnd
    ) {
        return new SourcePosition(
                "PDF", pageNumber, pageBlockOrdinal,
                pageCharacterStart, pageCharacterEnd,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null
        );
    }

    /** PDF 表格单元格，同时保留物理页位置和零基行列坐标。 */
    public static SourcePosition pdfTable(
            int pageNumber,
            int pageBlockOrdinal,
            int pageCharacterStart,
            int pageCharacterEnd,
            int tableRow,
            int tableColumn
    ) {
        return pdfTable(
                pageNumber, pageBlockOrdinal, pageCharacterStart, pageCharacterEnd,
                tableRow, tableColumn, 1, 1, null, null,
                null, null, null, null, null
        );
    }

    /** PDF 表格单元格的完整结构位置。 */
    public static SourcePosition pdfTable(
            int pageNumber,
            int pageBlockOrdinal,
            int pageCharacterStart,
            int pageCharacterEnd,
            int tableRow,
            int tableColumn,
            int tableRowSpan,
            int tableColumnSpan,
            String tableId,
            String tableColumnHeader
    ) {
        return pdfTable(
                pageNumber, pageBlockOrdinal, pageCharacterStart, pageCharacterEnd,
                tableRow, tableColumn, tableRowSpan, tableColumnSpan,
                tableId, tableColumnHeader,
                null, null, null, tableId, null
        );
    }

    /** PDF 表格单元格的完整结构位置和局部关系。 */
    public static SourcePosition pdfTable(
            int pageNumber,
            int pageBlockOrdinal,
            int pageCharacterStart,
            int pageCharacterEnd,
            int tableRow,
            int tableColumn,
            int tableRowSpan,
            int tableColumnSpan,
            String tableId,
            String tableColumnHeader,
            String tableCaption,
            String tableRowHeader,
            String tableCellHeading,
            String tableGroupId,
            String tableContinuationOf
    ) {
        return new SourcePosition(
                "PDF", pageNumber, pageBlockOrdinal,
                pageCharacterStart, pageCharacterEnd,
                null, tableRow, tableColumn, null,
                null, null, null, null,
                tableId, tableRowSpan, tableColumnSpan, tableColumnHeader,
                tableCaption, tableRowHeader, tableCellHeading,
                tableGroupId, tableContinuationOf
        );
    }

    /** DOCX 普通正文段落的位置。 */
    public static SourcePosition docxParagraph(int bodyElementIndex) {
        return new SourcePosition(
                "DOCX", null, null, null, null,
                bodyElementIndex, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null
        );
    }

    /** DOCX 表格单元格内段落的位置。 */
    public static SourcePosition docxTable(
            int bodyElementIndex,
            int row,
            int column,
            int cellParagraphIndex
    ) {
        return new SourcePosition(
                "DOCX", null, null, null, null,
                bodyElementIndex, row, column, cellParagraphIndex,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null, null
        );
    }

    /** Markdown 源码位置。 */
    public static SourcePosition markdown(
            int startLine,
            int startColumn,
            int endLine,
            int endColumn
    ) {
        return new SourcePosition(
                "MARKDOWN", null, null, null, null,
                null, null, null, null,
                startLine, startColumn, endLine, endColumn,
                null, null, null, null,
                null, null, null, null, null
        );
    }

    /** TXT 起止行位置。 */
    public static SourcePosition text(int startLine, int endLine) {
        return new SourcePosition(
                "TXT", null, null, null, null,
                null, null, null, null,
                startLine, null, endLine, null,
                null, null, null, null,
                null, null, null, null, null
        );
    }
}
