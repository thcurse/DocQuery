package com.doc.docquery.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ChatProfilesConfigTest {

    @Test
    void loadsPlainProfileNamesFromYamlWithoutBracketEscaping() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                "test-application",
                new ClassPathResource("application.yml")
        )) {
            environment.getPropertySources().addFirst(source);
        }

        ChatProfilesProperties profiles = new ChatProfilesConfig()
                .chatProfilesProperties(
                        environment,
                        new DocumentRetrievalProperties(),
                        new AnswerProperties()
                );

        assertThat(profiles.require("glm-5.3-flash").getProtocol())
                .isEqualTo("RESPONSES");
        assertThat(profiles.require("claude-sonnet-5").getProtocol())
                .isEqualTo("ANTHROPIC_MESSAGES");
    }

    @Test
    void readsPlainYamlStyleProfileNamesContainingDots() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "docquery.chat.profiles.glm-5.3-flash.model",
                        "glm-5.3-flash"
                )
                .withProperty(
                        "docquery.chat.profiles.glm-5.3-flash.protocol",
                        "RESPONSES"
                )
                .withProperty(
                        "docquery.chat.profiles.glm-5.3-flash.api-key",
                        "test-glm-key"
                )
                .withProperty(
                        "docquery.chat.profiles.claude-sonnet-5.model",
                        "claude-sonnet-5"
                )
                .withProperty(
                        "docquery.chat.profiles.claude-sonnet-5.protocol",
                        "ANTHROPIC_MESSAGES"
                )
                .withProperty(
                        "docquery.chat.profiles.claude-sonnet-5.api-key",
                        "test-sonnet-key"
                );
        DocumentRetrievalProperties retrieval = new DocumentRetrievalProperties();
        retrieval.setChatProfile("glm-5.3-flash");
        AnswerProperties answer = new AnswerProperties();
        answer.setChatProfile("claude-sonnet-5");

        ChatProfilesProperties profiles = new ChatProfilesConfig()
                .chatProfilesProperties(environment, retrieval, answer);

        assertThat(profiles.require("glm-5.3-flash"))
                .satisfies(profile -> {
                    assertThat(profile.getModel()).isEqualTo("glm-5.3-flash");
                    assertThat(profile.getProtocol()).isEqualTo("RESPONSES");
                    assertThat(profile.getApiKey()).isEqualTo("test-glm-key");
                });
        assertThat(profiles.require("claude-sonnet-5"))
                .satisfies(profile -> {
                    assertThat(profile.getModel()).isEqualTo("claude-sonnet-5");
                    assertThat(profile.getProtocol())
                            .isEqualTo("ANTHROPIC_MESSAGES");
                    assertThat(profile.getApiKey()).isEqualTo("test-sonnet-key");
                });
    }
}
