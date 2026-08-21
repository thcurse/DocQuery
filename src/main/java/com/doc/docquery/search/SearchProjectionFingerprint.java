package com.doc.docquery.search;

import com.doc.docquery.config.SearchProjectionProperties;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 对两份不可变输入和两份 Mapping 契约计算稳定投影身份。 */
@Component
public class SearchProjectionFingerprint {

    public String calculate(
            long documentVersionId,
            DocumentCanonicalArtifactEntity canonical,
            DocumentRetrievalArtifactEntity retrieval,
            SearchProjectionProperties properties
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 指纹只覆盖决定投影内容或索引身份的稳定业务字段，不混入 Endpoint、密钥或批大小。
            update(digest, Long.toString(documentVersionId));
            update(digest, canonical.getId().toString());
            update(digest, canonical.getCanonicalSha256());
            update(digest, retrieval.getId().toString());
            update(digest, retrieval.getRetrievalSha256());
            update(digest, properties.getEvidenceMappingVersion());
            update(digest, properties.getNavigationMappingVersion());
            update(digest, retrieval.getEmbeddingProvider());
            update(digest, retrieval.getEmbeddingModel());
            update(digest, retrieval.getEmbeddingDimension().toString());
            update(digest, retrieval.getEmbeddingTemplateVersion());
            update(digest, properties.getIndexPrefix());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** 长度前缀避免不同字段拼接后出现歧义。 */
    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
