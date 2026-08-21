package com.doc.docquery.retrieval;

import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 对模型输出做本地严格校验，防止“JSON 能解析”被误当成可持久化结果。 */
@Component
public class RetrievalSemanticValidator {

    public void validate(DocumentProfileSemantic value) {
        requireText(value == null ? null : value.purpose(), 400, "purpose");
        validateLists(value.topics(), value.aliases(), value.answerableQuestions(), 1);
    }

    public void validate(RetrievalNodeSemantic value) {
        requireText(value == null ? null : value.summary(), 600, "summary");
        // 标题、页眉或过渡节点可能没有可安全提出的问题；强制至少一项会诱导模型编造。
        validateLists(value.topics(), value.aliases(), value.answerableQuestions(), 0);
    }

    private void validateLists(
            List<String> topics,
            List<String> aliases,
            List<String> questions,
            int minimumQuestions
    ) {
        requireList(topics, 1, 12, 80, "topics");
        requireList(aliases, 0, 12, 80, "aliases");
        requireList(questions, minimumQuestions, 8, 200, "answerableQuestions");
    }

    private void requireList(
            List<String> values,
            int minimum,
            int maximum,
            int itemLimit,
            String field
    ) {
        if (values == null || values.size() < minimum || values.size() > maximum) {
            fail(field + " count is invalid");
        }
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            requireText(value, itemLimit, field);
            if (!unique.add(value.strip())) {
                fail(field + " contains duplicates");
            }
        }
    }

    private void requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            fail(field + " is missing or too long");
        }
    }

    private void fail(String message) {
        throw new RetrievalGenerationException(
                "RETRIEVAL_MODEL_OUTPUT_INVALID",
                message,
                false
        );
    }
}
