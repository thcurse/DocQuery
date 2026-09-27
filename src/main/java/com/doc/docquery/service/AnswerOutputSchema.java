package com.doc.docquery.service;

import com.doc.docquery.config.ChatProfilesProperties;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.*;
import java.util.List;

/** Same structural contract for ordinary and streaming final answers. */
public final class AnswerOutputSchema {
    private AnswerOutputSchema() {}
    public static ResponseFormat format(ChatProfilesProperties.Profile profile) {
        return switch (profile.effectiveFinalOutputMode()) {
            case "PROMPT_ONLY" -> ResponseFormat.TEXT;
            case "JSON_OBJECT" -> ResponseFormat.JSON;
            default -> ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(JsonSchema.builder()
                    .name("docquery_answer")
                    .rootElement(JsonObjectSchema.builder()
                            .addEnumProperty("status", List.of("ANSWERED", "INSUFFICIENT_EVIDENCE"))
                            .addProperty("answer", JsonAnyOfSchema.builder().anyOf(
                                    JsonStringSchema.builder().build(), new JsonNullSchema()).build())
                            .addProperty("evidenceIds", JsonArraySchema.builder()
                                    .items(JsonStringSchema.builder().build()).build())
                            .required("status", "answer", "evidenceIds").additionalProperties(false).build())
                    .build()).build();
        };
    }
}
