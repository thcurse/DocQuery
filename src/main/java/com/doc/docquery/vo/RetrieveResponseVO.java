package com.doc.docquery.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** `/retrieve` 的可重放成功响应；所有 evidence 文本均来自 canonical。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RetrieveResponseVO {

    private String queryExecutionId;
    private Long knowledgeBaseId;
    private String requestedMode;
    private String executedMode;
    private boolean degraded;
    private String degradationReason;
    private List<Result> results;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {
        private int rank;
        private Long documentId;
        private Long documentVersionId;
        private Integer versionNo;
        private String documentName;
        private String headingNodeId;
        private List<String> headingPath;
        private List<String> channels;
        private Integer keywordRank;
        private Integer semanticRank;
        private List<Evidence> evidence;
        /** 仅供同一 Answer 执行上下文注册最小读取范围，不进入外部 /retrieve。 */
        @JsonIgnore
        private InternalReadTarget internalReadTarget;
    }

    public record InternalReadTarget(
            String cardId,
            String cardType,
            int sectionStartBlockOrdinal,
            int sectionEndBlockOrdinalExclusive
    ) {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Evidence {
        private String blockId;
        private String kind;
        private String text;
        private boolean truncated;
        private long canonicalStart;
        private long canonicalEnd;
        private SourcePosition sourcePosition;
        private List<HighlightFragment> keywordHighlights;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourcePosition {
        private String sourceType;
        private Integer pageNumber;
        private Integer pageBlockOrdinal;
        private Integer pageCharacterStart;
        private Integer pageCharacterEnd;
        private Integer bodyElementIndex;
        private Integer tableRow;
        private Integer tableColumn;
        private Integer cellParagraphIndex;
        private Integer startLine;
        private Integer startColumn;
        private Integer endLine;
        private Integer endColumn;
        private String tableId;
        private Integer tableRowSpan;
        private Integer tableColumnSpan;
        private String tableColumnHeader;

        public SourcePosition(
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
                Integer endColumn
        ) {
            this(
                    sourceType, pageNumber, pageBlockOrdinal,
                    pageCharacterStart, pageCharacterEnd, bodyElementIndex,
                    tableRow, tableColumn, cellParagraphIndex,
                    startLine, startColumn, endLine, endColumn,
                    null, null, null, null
            );
        }
    }

    /** 高亮片段由纯文本段组成，避免把 ES 生成的标签直接交给业务页面。 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HighlightFragment {
        private List<HighlightSegment> segments;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HighlightSegment {
        private String text;
        private boolean matched;
    }
}
