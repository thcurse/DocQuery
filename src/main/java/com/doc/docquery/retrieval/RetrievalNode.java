package com.doc.docquery.retrieval;

import com.doc.docquery.parser.SourcePosition;

import java.util.List;

/** 一个真实 canonical 标题节点对应的可检索导航卡。 */
public record RetrievalNode(
        String cardId,
        String cardType,
        Integer partitionOrdinal,
        String headingNodeId,
        String parentHeadingNodeId,
        int siblingOrder,
        int depth,
        String title,
        String titlePath,
        int sectionStartBlockOrdinal,
        int sectionEndBlockOrdinalExclusive,
        long canonicalStart,
        long canonicalEnd,
        SourcePosition sourceStart,
        SourcePosition sourceEnd,
        String summary,
        List<String> topics,
        List<String> aliases,
        List<String> answerableQuestions,
        String embeddingText,
        EmbeddingPayload embedding
) {
    public static final String HEADING_NODE = "HEADING_NODE";
    public static final String HEADING_SUBPARTITION = "HEADING_SUBPARTITION";

    public RetrievalNode {
        cardType = cardType == null ? HEADING_NODE : cardType;
        topics = List.copyOf(topics);
        aliases = List.copyOf(aliases);
        answerableQuestions = List.copyOf(answerableQuestions);
    }

    /** 兼容 schema v1 代码与旧 JSONL：旧节点都是完整真实章节卡。 */
    public RetrievalNode(
            String cardId,
            String headingNodeId,
            String parentHeadingNodeId,
            int siblingOrder,
            int depth,
            String title,
            String titlePath,
            int sectionStartBlockOrdinal,
            int sectionEndBlockOrdinalExclusive,
            long canonicalStart,
            long canonicalEnd,
            SourcePosition sourceStart,
            SourcePosition sourceEnd,
            String summary,
            List<String> topics,
            List<String> aliases,
            List<String> answerableQuestions,
            String embeddingText,
            EmbeddingPayload embedding
    ) {
        this(cardId, HEADING_NODE, null, headingNodeId, parentHeadingNodeId,
                siblingOrder, depth, title, titlePath, sectionStartBlockOrdinal,
                sectionEndBlockOrdinalExclusive, canonicalStart, canonicalEnd,
                sourceStart, sourceEnd, summary, topics, aliases,
                answerableQuestions, embeddingText, embedding);
    }
}
