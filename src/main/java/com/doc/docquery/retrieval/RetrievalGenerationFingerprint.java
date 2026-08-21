package com.doc.docquery.retrieval;

import com.doc.docquery.config.DocumentRetrievalProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 计算不含密钥、Endpoint Workspace ID、超时和重试次数的稳定配置指纹。 */
@Component
public class RetrievalGenerationFingerprint {

    private final DocumentRetrievalProperties properties;
    private final EmbeddingCodec codec;

    public RetrievalGenerationFingerprint(
            DocumentRetrievalProperties properties,
            EmbeddingCodec codec
    ) {
        this.properties = properties;
        this.codec = codec;
    }

    public String calculate(String canonicalSha256) {
        String value = String.join("\n",
                canonicalSha256,
                Integer.toString(properties.getSchemaVersion()),
                "DEEPSEEK",
                properties.getChatModel(),
                "DISABLED",
                properties.getChatPromptVersion(),
                "ALIBABA_MODEL_STUDIO",
                properties.getEmbeddingModel(),
                Integer.toString(properties.getEmbeddingDimension()),
                properties.getEmbeddingTemplateVersion()
        );
        return codec.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
