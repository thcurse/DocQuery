package com.doc.docquery.retrieval;

import org.springframework.stereotype.Component;

/** 构造稳定、可版本化的导航向量文本，避免向量直接依赖 JSON 序列化细节。 */
@Component
public class NavigationTextBuilder {

    public String profile(String documentTitle, DocumentProfileSemantic semantic) {
        return "Document: " + documentTitle + '\n'
                + "Section: ROOT\n"
                + "Purpose: " + semantic.purpose() + '\n'
                + "Topics: " + String.join(" | ", semantic.topics()) + '\n'
                + "Aliases: " + String.join(" | ", semantic.aliases()) + '\n'
                + "Questions: " + String.join(" | ", semantic.answerableQuestions());
    }

    public String node(
            String documentTitle,
            String titlePath,
            RetrievalNodeSemantic semantic
    ) {
        return "Document: " + documentTitle + '\n'
                + "Section: " + titlePath + '\n'
                + "Summary: " + semantic.summary() + '\n'
                + "Topics: " + String.join(" | ", semantic.topics()) + '\n'
                + "Aliases: " + String.join(" | ", semantic.aliases()) + '\n'
                + "Questions: " + String.join(" | ", semantic.answerableQuestions());
    }
}
