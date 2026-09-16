package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Answer 与离线检索卡共用的模型连接目录；profile 名必须等于真实模型名。 */
@Getter
@Setter
public class ChatProfilesProperties {

    private Map<String, Profile> profiles = new LinkedHashMap<>();

    public Profile require(String profileName) {
        if (!StringUtils.hasText(profileName)) {
            throw new IllegalStateException("Chat profile name is required");
        }
        Profile profile = profiles.get(profileName);
        if (profile == null) {
            throw new IllegalStateException("Chat profile is not configured: " + profileName);
        }
        if (!StringUtils.hasText(profile.getModel())
                || !profileName.equals(profile.getModel())) {
            throw new IllegalStateException(
                    "Chat profile name must equal its model: " + profileName
            );
        }
        return profile;
    }

    @Getter
    @Setter
    public static class Profile {

        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private String provider = "PACKY_API";
        private String protocol = "RESPONSES";
        private String thinkingMode = "PROVIDER_DEFAULT";
        /** 仅用于原生支持 SDK 重试上限的协议客户端。 */
        private int maxRetries;

        public String normalizedProtocol() {
            return protocol == null ? "" : protocol.strip().toUpperCase(Locale.ROOT);
        }
    }
}
