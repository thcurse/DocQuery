package com.doc.docquery.retrieval;

import com.doc.docquery.parser.SourcePosition;

import java.util.List;

/** 一个真实 canonical 标题节点对应的可检索导航卡。 */
public record RetrievalNode(
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
    public RetrievalNode {
        topics = List.copyOf(topics);
        aliases = List.copyOf(aliases);
        answerableQuestions = List.copyOf(answerableQuestions);
    }
}
