package com.doc.docquery.retrieval;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按 header/profile/nodes/footer 顺序写单一不可变 retrieval.jsonl。 */
@Component
public class RetrievalJsonlWriter {

    private final ObjectMapper objectMapper;
    private final DocumentRetrievalProperties properties;

    public RetrievalJsonlWriter(
            ObjectMapper objectMapper,
            DocumentRetrievalProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public WrittenArtifact write(
            RetrievalArtifact artifact,
            RetrievalArtifactValidator.ValidationResult validation
    ) {
        Path path = null;
        try {
            path = Files.createTempFile("docquery-retrieval-", ".jsonl");
            MessageDigest digest = sha256Digest();
            try (OutputStream file = Files.newOutputStream(path);
                 OutputStream limited = new LimitedOutputStream(
                         file,
                         properties.getMaxRetrievalBytes()
                 );
                 DigestOutputStream digested = new DigestOutputStream(limited, digest);
                 BufferedOutputStream output = new BufferedOutputStream(digested)) {
                writeLine(output, header(artifact));
                writeLine(output, record("profile", artifact.profile()));
                for (RetrievalNode node : artifact.nodes()) {
                    writeLine(output, record("node", node));
                }
                writeLine(output, footer(artifact, validation));
            }
            return new WrittenArtifact(
                    path,
                    Files.size(path),
                    HexFormat.of().formatHex(digest.digest()),
                    validation.semanticSha256(),
                    validation.vectorSha256()
            );
        } catch (RetrievalGenerationException exception) {
            deleteQuietly(path);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(path);
            throw new RetrievalGenerationException(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Retrieval JSONL could not be materialized",
                    true,
                    exception
            );
        }
    }

    private Map<String, Object> header(RetrievalArtifact artifact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recordType", "header");
        value.put("schemaVersion", artifact.schemaVersion());
        value.put("documentVersionId", artifact.documentVersionId());
        value.put("canonicalArtifactSha256", artifact.canonicalSha256());
        value.put("chatProvider", "DEEPSEEK");
        value.put("chatModel", artifact.chatModel());
        value.put("promptVersion", artifact.chatPromptVersion());
        value.put("thinkingMode", "DISABLED");
        value.put("embeddingProvider", "ALIBABA_MODEL_STUDIO");
        value.put("embeddingModel", artifact.embeddingModel());
        value.put("embeddingDimension", artifact.embeddingDimension());
        value.put("embeddingTemplateVersion", artifact.embeddingTemplateVersion());
        value.put("generationFingerprint", artifact.generationFingerprint());
        value.put("generatedAt", artifact.generatedAt().toString());
        return value;
    }

    private Map<String, Object> footer(
            RetrievalArtifact artifact,
            RetrievalArtifactValidator.ValidationResult validation
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recordType", "footer");
        value.put("profileCount", 1);
        value.put("nodeCount", artifact.nodes().size());
        value.put("vectorCount", artifact.nodes().size() + 1);
        value.put("semanticSha256", validation.semanticSha256());
        value.put("vectorSha256", validation.vectorSha256());
        value.put("generationFingerprint", artifact.generationFingerprint());
        value.put("complete", true);
        return value;
    }

    private Map<String, Object> record(String type, Object recordValue) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recordType", type);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = objectMapper.convertValue(recordValue, Map.class);
        value.putAll(fields);
        return value;
    }

    private void writeLine(OutputStream output, Object value) throws IOException {
        output.write(objectMapper.writeValueAsBytes(value));
        output.write('\n');
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不能掩盖原始生成错误。
        }
    }

    public record WrittenArtifact(
            Path path,
            long sizeBytes,
            String sha256,
            String semanticSha256,
            String vectorSha256
    ) {
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long limit;
        private long count;

        private LimitedOutputStream(OutputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(buffer, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void ensureCapacity(int length) {
            if (count + length > limit) {
                throw new RetrievalGenerationException(
                        "RETRIEVAL_GENERATION_LIMIT_EXCEEDED",
                        "Retrieval JSONL exceeds configured limit",
                        false
                );
            }
        }
    }
}
