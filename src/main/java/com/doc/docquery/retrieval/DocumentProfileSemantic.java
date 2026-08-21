package com.doc.docquery.retrieval;

import java.util.List;

/** 模型为整份文档生成的纯语义字段，不允许模型决定任何业务 ID。 */
public record DocumentProfileSemantic(
        String purpose,
        List<String> topics,
        List<String> aliases,
        List<String> answerableQuestions
) {
    public DocumentProfileSemantic {
        purpose = RetrievalSemanticNormalization.boundedText(purpose, 400);
        topics = RetrievalSemanticNormalization.boundedList(topics, 12, 80);
        aliases = RetrievalSemanticNormalization.boundedList(aliases, 12, 80);
        answerableQuestions = RetrievalSemanticNormalization.boundedList(
                answerableQuestions, 8, 200
        );
    }
}
