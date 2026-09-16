package com.doc.docquery.config;

import com.doc.docquery.service.AnswerAgentGateway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式真实 Sonnet Tool Calling 冒烟；默认回归不调用收费供应商。 */
class AnswerProviderSmokeTest {

    @Test
    void realPackyGptCompletesOneToolCallAndReturnsStrictFinalJson() {
        String apiKey = System.getenv("DOCQUERY_CLAUDE_SONNET_5_API_KEY");
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(
                        System.getenv("DOCQUERY_REAL_PROVIDER_SMOKE_ENABLED")
                ) && hasText(apiKey),
                "Real Answer smoke requires explicit enablement and PackyAPI key"
        );

        ChatProfilesProperties profiles = new ChatProfilesProperties();
        ChatProfilesProperties.Profile profile = new ChatProfilesProperties.Profile();
        profile.setModel("claude-sonnet-5");
        profile.setProtocol("ANTHROPIC_MESSAGES");
        profile.setApiKey(apiKey);
        String configuredBaseUrl = System.getenv("DOCQUERY_CLAUDE_SONNET_5_BASE_URL");
        if (hasText(configuredBaseUrl)) {
            profile.setBaseUrl(configuredBaseUrl);
        } else {
            profile.setBaseUrl("https://slb-v1.api.fan/v1");
        }
        profiles.setProfiles(java.util.Map.of("claude-sonnet-5", profile));
        AnswerProperties answer = new AnswerProperties();
        answer.setMaxOutputTokens(512);
        RetrievalProviderConfig config = new RetrievalProviderConfig();
        AnswerAgentGateway gateway = config.answerAgentGateway(
                config.answerChatModel(profiles, answer)
        );

        AnswerAgentGateway.AgentRun run = gateway.start(new AnswerAgentGateway.Request("""
                This is a Tool Calling protocol smoke test. First call search
                exactly once with query "refund". After the tool result,
                call submit_evidence with {"evidenceIds":["E1"]}.
                """, 10),
                (name, arguments) -> {
                    assertThat(name).isEqualTo("search");
                    assertThat(arguments).contains("refund");
                    return "{\"status\":\"OK\",\"candidates\":[{\"evidence\":[{"
                            + "\"evidenceId\":\"E1\",\"text\":\"refund policy\"}]}]}";
                },
                new NoopObserver()
        );
        String output = run.next("Run the protocol test.");

        JsonNode result = new ObjectMapper().readTree(output);
        assertThat(result.get("evidenceIds"))
                .extracting(JsonNode::asText)
                .containsExactly("E1");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class NoopObserver implements AnswerAgentGateway.Observer {
        @Override
        public void beforeModelCall() {
        }

        @Override
        public void toolRound(int requestedCalls) {
        }

        @Override
        public void afterToolCall() {
        }
    }
}
