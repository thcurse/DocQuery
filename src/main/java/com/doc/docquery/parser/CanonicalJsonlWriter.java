package com.doc.docquery.parser;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.service.DocumentParseException;
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

/** 将已验证文档逐条写成 canonical JSONL v1 临时文件并计算对象摘要。 */
@Component
public class CanonicalJsonlWriter {

    private final ObjectMapper objectMapper;
    private final DocumentParsingProperties properties;

    public CanonicalJsonlWriter(
            ObjectMapper objectMapper,
            DocumentParsingProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public WrittenArtifact write(CanonicalDocument document) {
        Path path = null;
        try {
            path = Files.createTempFile("docquery-canonical-", ".jsonl");
            MessageDigest digest = sha256Digest();
            try (OutputStream file = Files.newOutputStream(path);
                 OutputStream limited = new LimitedOutputStream(
                         file,
                         properties.getMaxCanonicalBytes()
                 );
                 DigestOutputStream digested = new DigestOutputStream(limited, digest);
                 BufferedOutputStream output = new BufferedOutputStream(digested)) {
                writeLine(output, header(document));
                for (EvidenceBlock block : document.blocks()) {
                    writeLine(output, record("block", block));
                }
                for (HeadingNode heading : document.headings()) {
                    writeLine(output, record("heading", heading));
                }
                for (ParseWarning warning : document.warnings()) {
                    writeLine(output, record("warning", warning));
                }
                writeLine(output, footer(document));
            }
            return new WrittenArtifact(
                    path,
                    Files.size(path),
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (DocumentParseException exception) {
            deleteQuietly(path);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(path);
            throw new DocumentParseException(
                    "CANONICAL_OBJECT_UNAVAILABLE",
                    "Canonical JSONL could not be materialized",
                    true,
                    exception
            );
        }
    }

    private Map<String, Object> header(CanonicalDocument document) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("recordType", "header");
        header.put("schemaVersion", document.schemaVersion());
        header.put("documentVersionId", document.documentVersionId());
        header.put("sourceFormat", document.sourceFormat());
        header.put("sourceSha256", document.sourceSha256());
        header.put("parserName", document.parserName());
        header.put("parserVersion", document.parserVersion());
        header.put("generatedAt", document.generatedAt().toString());
        return header;
    }

    private Map<String, Object> footer(CanonicalDocument document) {
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("recordType", "footer");
        footer.put("blockCount", document.blocks().size());
        footer.put("headingCount", document.headings().size());
        footer.put("warningCount", document.warnings().size());
        footer.put("pageCount", document.pageCount());
        footer.put("textLength", document.textLength());
        footer.put("canonicalTextSha256", document.canonicalTextSha256());
        footer.put("complete", true);
        return footer;
    }

    /** record 会被展开到顶层，便于后续按行流式消费。 */
    private Map<String, Object> record(String recordType, Object value) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordType", recordType);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = objectMapper.convertValue(value, Map.class);
        record.putAll(fields);
        return record;
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
            // JVM 临时目录清理失败不能覆盖原始标准化失败。
        }
    }

    /** 调用方在对象上传结束后必须删除 path。 */
    public record WrittenArtifact(Path path, long sizeBytes, String sha256) {
    }

    /** 对实际 JSONL 字节做硬限制，避免只限制提取文本而忽略 JSON 开销。 */
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
                throw new DocumentParseException(
                        "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                        "Canonical JSONL exceeds configured limit",
                        false
                );
            }
        }
    }
}
