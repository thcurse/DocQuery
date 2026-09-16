package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** `/answer` 可精确重放的成功终态；引用文本全部来自 canonical。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnswerResponseVO {

    private String queryExecutionId;
    private Long knowledgeBaseId;
    private String status;
    private String answer;
    private String requestedMode;
    private String executedMode;
    private boolean degraded;
    private String degradationReason;
    private List<Citation> citations;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        private Integer citationIndex;
        private Long documentId;
        private Long documentVersionId;
        private Integer versionNo;
        private String documentName;
        private String headingNodeId;
        private List<String> headingPath;
        private String blockId;
        private String text;
        private boolean truncated;
        private long canonicalStart;
        private long canonicalEnd;
        private RetrieveResponseVO.SourcePosition sourcePosition;

        /** 用户可理解的 PDF 物理页；内部 block 定位不会作为模型引用语义。 */
        public Integer getPageNumber() {
            return sourcePosition == null ? null : sourcePosition.getPageNumber();
        }
    }
}
