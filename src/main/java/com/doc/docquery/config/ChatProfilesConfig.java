package com.doc.docquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按完整模型名读取 Chat profile。绕开 Spring 对复杂 Map 键中点号的层级拆分，
 * 使 secrets 可以直接使用 `glm-5.3-flash:`，无需方括号转义。
 */
@Configuration(proxyBeanMethods = false)
public class ChatProfilesConfig {

    @Bean
    ChatProfilesProperties chatProfilesProperties(
            Environment environment,
            DocumentRetrievalProperties retrieval,
            AnswerProperties answer
    ) {
        Map<String, ChatProfilesProperties.Profile> profiles = new LinkedHashMap<>();
        profiles.put(
                retrieval.getChatProfile(),
                readProfile(environment, retrieval.getChatProfile())
        );
        profiles.putIfAbsent(
                answer.getChatProfile(),
                readProfile(environment, answer.getChatProfile())
        );
        ChatProfilesProperties properties = new ChatProfilesProperties();
        properties.setProfiles(profiles);
        return properties;
    }

    private ChatProfilesProperties.Profile readProfile(
            Environment environment,
            String profileName
    ) {
        String prefix = "docquery.chat.profiles." + profileName + ".";
        ChatProfilesProperties.Profile profile = new ChatProfilesProperties.Profile();
        profile.setModel(environment.getProperty(prefix + "model", ""));
        profile.setProvider(environment.getProperty(prefix + "provider", "PACKY_API"));
        profile.setProtocol(environment.getProperty(prefix + "protocol", "RESPONSES"));
        profile.setThinkingMode(environment.getProperty(
                prefix + "thinking-mode",
                "PROVIDER_DEFAULT"
        ));
        profile.setBaseUrl(environment.getProperty(prefix + "base-url", ""));
        profile.setApiKey(environment.getProperty(prefix + "api-key", ""));
        profile.setMaxRetries(environment.getProperty(
                prefix + "max-retries",
                Integer.class,
                0
        ));
        return profile;
    }
}
