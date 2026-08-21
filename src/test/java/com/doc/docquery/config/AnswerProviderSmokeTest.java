package com.doc.docquery.config;

import com.doc.docquery.service.AnswerChatGateway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式真实 DeepSeek Tool Calling 冒烟；默认回归不调用收费供应商。 */
class AnswerProviderSmokeTest {

    @Test
    void realDeepSeekCompletesOneToolCallAndReturnsStrictFinalJson() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(
                        System.getenv("DOCQUERY_REAL_PROVIDER_SMOKE_ENABLED")
                ) && hasText(apiKey),
                "Real Answer smoke requires explicit enablement and DeepSeek key"
        );

        DocumentRetrievalProperties provider = new DocumentRetrievalProperties();
        provider.setChatApiKey(apiKey);
        String configuredBaseUrl = System.getenv("DOCQUERY_DEEPSEEK_BASE_URL");
        if (hasText(configuredBaseUrl)) {
            provider.setChatBaseUrl(configuredBaseUrl);
        }
        AnswerProperties answer = new AnswerProperties();
        answer.setMaxOutputTokens(512);
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        AnswerChatGateway gateway = config.answerChatGateway(
                config.answerChatModel(provider, answer),
                answer
        );

        List<AnswerChatGateway.Message> messages = new ArrayList<>();
        messages.add(new AnswerChatGateway.SystemPrompt("""
                This is a Tool Calling protocol smoke test. First call searchDocuments
                exactly once with query "refund" and limit 1. After the tool result,
                return JSON only: {"status":"ANSWERED","answer":"ok [E1]",
                "citedEvidenceIds":["E1"]}.
                """));
        messages.add(new AnswerChatGateway.UserContent("Run the protocol test."));

        AnswerChatGateway.Turn toolTurn = gateway.chat(List.copyOf(messages), true);
        assertThat(toolTurn.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.name()).isEqualTo("searchDocuments"));
        AnswerChatGateway.ToolCall call = toolTurn.toolCalls().get(0);
        messages.add(new AnswerChatGateway.AssistantContent(
                toolTurn.text(), toolTurn.toolCalls()
        ));
        messages.add(new AnswerChatGateway.ToolResultContent(
                call.id(),
                call.name(),
                "{\"status\":\"OK\",\"documents\":[{\"evidence\":[{"
                        + "\"evidenceId\":\"E1\",\"text\":\"refund policy\"}]}]}"
        ));

        AnswerChatGateway.Turn finalTurn = gateway.chat(List.copyOf(messages), false);
        assertThat(finalTurn.toolCalls()).isEmpty();
        JsonNode result = new ObjectMapper().readTree(finalTurn.text());
        assertThat(result.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(result.get("answer").asText()).contains("[E1]");
        assertThat(result.get("citedEvidenceIds").get(0).asText()).isEqualTo("E1");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
