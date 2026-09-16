package com.doc.docquery.retrieval;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.config.ChatProfilesProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 计算不含密钥、Endpoint Workspace ID、超时和重试次数的稳定配置指纹。 */
@Component
public class RetrievalGenerationFingerprint {

    private final DocumentRetrievalProperties properties;
    private final ChatProfilesProperties chatProfiles;
    private final EmbeddingCodec codec;

    public RetrievalGenerationFingerprint(
            DocumentRetrievalProperties properties,
            ChatProfilesProperties chatProfiles,
            EmbeddingCodec codec
    ) {
        this.properties = properties;
        this.chatProfiles = chatProfiles;
        this.codec = codec;
    }

    public String calculate(String canonicalSha256) {
        ChatProfilesProperties.Profile chatProfile = chatProfiles.require(
                properties.getChatProfile()
        );
        String value = String.join("\n",
                canonicalSha256,
                Integer.toString(properties.getSchemaVersion()),
                chatProfile.getProvider(),
                chatProfile.normalizedProtocol(),
                chatProfile.getModel(),
                chatProfile.getThinkingMode(),
                properties.getChatPromptVersion(),
                Integer.toString(properties.getMaxSourceTokensPerChatCall()),
                Integer.toString(properties.getMaxItemsPerChatCall()),
                Boolean.toString(properties.isNavigationPartitionEnabled()),
                properties.getNavigationPartitionEstimatorVersion(),
                Integer.toString(properties.getNavigationPartitionThresholdTokens()),
                Integer.toString(properties.getNavigationPartitionMinimumTokens()),
                Integer.toString(properties.getNavigationPartitionMaxCount()),
                Integer.toString(properties.getNavigationPartitionMaxCandidates()),
                "ALIBABA_MODEL_STUDIO",
                properties.getEmbeddingModel(),
                Integer.toString(properties.getEmbeddingDimension()),
                properties.getEmbeddingTemplateVersion()
        );
        return codec.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
