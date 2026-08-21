package com.doc.docquery.retrieval;

import java.util.LinkedHashSet;
import java.util.List;

/** 对模型语义字段做确定性清理并收敛到已冻结的持久化硬边界。 */
final class RetrievalSemanticNormalization {

    private RetrievalSemanticNormalization() {
    }

    static String text(String value) {
        return value == null ? null : value.strip();
    }

    static String boundedText(String value, int maximum) {
        String normalized = text(value);
        if (normalized == null || normalized.length() <= maximum) {
            return normalized;
        }
        int end = maximum;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))
                && end < normalized.length()
                && Character.isLowSurrogate(normalized.charAt(end))) {
            end--;
        }
        return normalized.substring(0, end).stripTrailing();
    }

    static List<String> list(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String text = text(value);
            if (text != null && !text.isEmpty()) {
                normalized.add(text);
            }
        }
        return List.copyOf(normalized);
    }

    static List<String> boundedList(
            List<String> values,
            int maximumItems,
            int itemLimit
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = boundedText(value, itemLimit);
            if (item != null && !item.isEmpty()) {
                normalized.add(item);
                if (normalized.size() == maximumItems) {
                    break;
                }
            }
        }
        return List.copyOf(normalized);
    }
}
