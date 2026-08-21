package com.doc.docquery.retrieval;

import java.util.List;

/** 模型为一个真实标题节点生成的纯语义字段。 */
public record RetrievalNodeSemantic(
        String summary,
        List<String> topics,
        List<String> aliases,
        List<String> answerableQuestions
) {
    public RetrievalNodeSemantic {
        summary = RetrievalSemanticNormalization.boundedText(summary, 600);
        topics = RetrievalSemanticNormalization.boundedList(topics, 12, 80);
        aliases = RetrievalSemanticNormalization.boundedList(aliases, 12, 80);
        answerableQuestions = RetrievalSemanticNormalization.boundedList(
                answerableQuestions, 8, 200
        );
    }
}
