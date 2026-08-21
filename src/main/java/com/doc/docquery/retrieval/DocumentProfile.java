package com.doc.docquery.retrieval;

import java.util.List;

/** 根节点对应的文档级检索卡；它不伪造 canonical 标题节点。 */
public record DocumentProfile(
        String cardId,
        long documentVersionId,
        String documentTitle,
        String purpose,
        List<String> topics,
        List<String> aliases,
        List<String> answerableQuestions,
        String embeddingText,
        EmbeddingPayload embedding
) {
    public DocumentProfile {
        topics = List.copyOf(topics);
        aliases = List.copyOf(aliases);
        answerableQuestions = List.copyOf(answerableQuestions);
    }
}
