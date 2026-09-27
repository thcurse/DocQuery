package com.doc.docquery.config;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AnswerProfileIdentityTest {
    @Test void dottedProfileNameReadsExplicitOutputModeAndDefaultsStayProtocolSpecific() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("docquery.chat.profiles.grok-4.7.model", "grok-4.7")
                .withProperty("docquery.chat.profiles.grok-4.7.final-output-mode", "JSON_SCHEMA");
        var retrieval = new DocumentRetrievalProperties(); retrieval.setChatProfile("grok-4.7");
        var answer = new AnswerProperties(); answer.setChatProfile("grok-4.7");
        var profile = new ChatProfilesConfig().chatProfilesProperties(env,retrieval,answer).getProfiles().get("grok-4.7");
        assertThat(profile.effectiveFinalOutputMode()).isEqualTo("JSON_SCHEMA");
        profile.setFinalOutputMode(null);
        assertThat(profile.effectiveFinalOutputMode()).isEqualTo("JSON_OBJECT");
        profile.setProtocol("ANTHROPIC_MESSAGES");
        assertThat(profile.effectiveFinalOutputMode()).isEqualTo("PROMPT_ONLY");
        profile.setFinalOutputMode("guess-from-model");
        assertThatThrownBy(profile::effectiveFinalOutputMode).isInstanceOf(IllegalStateException.class);
    }

    @Test void secretsAreExcludedWhileEffectiveModelSettingsChangeIdentity() {
        var p = new ChatProfilesProperties.Profile();p.setModel("deepseek-flash");p.setBaseUrl("https://example.test/v1/");
        String original=p.answerIdentity();p.setApiKey("SECRET-KEY");
        assertThat(p.answerIdentity()).isEqualTo(original).doesNotContain("SECRET-KEY","https://");
        p.setBaseUrl("https://example.test/v1");assertThat(p.answerIdentity()).isEqualTo(original);
        p.setFinalOutputMode("JSON_SCHEMA");assertThat(p.answerIdentity()).isNotEqualTo(original);
        String schema=p.answerIdentity();p.setReasoningEffort("high");assertThat(p.answerIdentity()).isNotEqualTo(schema);
        String effort=p.answerIdentity();p.setProtocol("CHAT_COMPLETIONS");assertThat(p.answerIdentity()).isNotEqualTo(effort);
    }
}
