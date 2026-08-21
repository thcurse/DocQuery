package com.doc.docquery.service.impl;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.parser.CanonicalDocumentAssembler;
import com.doc.docquery.parser.CanonicalDocumentValidator;
import com.doc.docquery.parser.CanonicalJsonlWriter;
import com.doc.docquery.parser.DocumentFormatParser;
import com.doc.docquery.parser.ParsedDocument;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentCanonicalService;
import com.doc.docquery.service.DocumentParseException;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.SourceObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * N2.3 标准化编排：验证原文件、调用格式 Parser、写单一对象并登记 MySQL 清单。
 *
 * <p>网络 I/O 不包在长数据库事务中；唯一约束解决并发胜者，失败执行者只清理
 * 自己生成的随机 Key。</p>
 */
@Service
@ConditionalOnBean({SourceObjectStore.class, CanonicalArtifactStore.class})
public class DocumentCanonicalServiceImpl implements DocumentCanonicalService {

    private static final Logger LOG = LoggerFactory.getLogger(
            DocumentCanonicalServiceImpl.class
    );
    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentMapper documentMapper;
    private final DocumentCanonicalArtifactMapper artifactMapper;
    private final SourceObjectStore sourceObjectStore;
    private final CanonicalArtifactStore canonicalArtifactStore;
    private final List<DocumentFormatParser> parsers;
    private final CanonicalDocumentAssembler assembler;
    private final CanonicalDocumentValidator validator;
    private final CanonicalJsonlWriter writer;
    private final DocumentParsingProperties properties;

    public DocumentCanonicalServiceImpl(
            DocumentVersionMapper documentVersionMapper,
            DocumentMapper documentMapper,
            DocumentCanonicalArtifactMapper artifactMapper,
            SourceObjectStore sourceObjectStore,
            CanonicalArtifactStore canonicalArtifactStore,
            List<DocumentFormatParser> parsers,
            CanonicalDocumentAssembler assembler,
            CanonicalDocumentValidator validator,
            CanonicalJsonlWriter writer,
            DocumentParsingProperties properties
    ) {
        this.documentVersionMapper = documentVersionMapper;
        this.documentMapper = documentMapper;
        this.artifactMapper = artifactMapper;
        this.sourceObjectStore = sourceObjectStore;
        this.canonicalArtifactStore = canonicalArtifactStore;
        this.parsers = List.copyOf(parsers);
        this.assembler = assembler;
        this.validator = validator;
        this.writer = writer;
        this.properties = properties;
    }

    @Override
    public DocumentCanonicalArtifactEntity ensureCanonical(long documentVersionId) {
        try {
            return ensureCanonicalInternal(documentVersionId);
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            // 将实现细节不同的数据库异常收敛为 Consumer 可识别的稳定重试契约。
            throw retryable(
                    "PROCESSING_DATABASE_UNAVAILABLE",
                    "Canonical artifact database operation is temporarily unavailable",
                    exception
            );
        }
    }

    private DocumentCanonicalArtifactEntity ensureCanonicalInternal(long documentVersionId) {
        if (documentVersionId < 1) {
            throw permanent("DOCUMENT_VERSION_NOT_FOUND", "Document version is invalid");
        }
        DocumentCanonicalArtifactEntity existing = artifactMapper
                .findByDocumentVersionId(documentVersionId);
        if (existing != null) {
            verifyExisting(existing);
            return existing;
        }

        DocumentVersionEntity version = documentVersionMapper.findById(documentVersionId);
        if (version == null) {
            throw permanent("DOCUMENT_VERSION_NOT_FOUND", "Document version does not exist");
        }
        DocumentEntity document = documentMapper.findByTenantAndId(
                version.getTenantId(),
                version.getDocumentId()
        );
        if (document == null) {
            throw permanent("DOCUMENT_VERSION_NOT_FOUND", "Document facts do not match");
        }
        if (!DocumentVersionStatus.PROCESSING.getCode().equals(version.getStatus())) {
            throw permanent(
                    "DOCUMENT_VERSION_NOT_PROCESSING",
                    "Only a processing document version can be standardized"
            );
        }
        if (!sourceObjectStore.bucketName().equals(version.getSourceBucket())) {
            throw permanent(
                    "SOURCE_OBJECT_MISSING",
                    "Source object reference uses an unavailable bucket"
            );
        }

        Path sourcePath = null;
        CanonicalJsonlWriter.WrittenArtifact written = null;
        String objectKey = null;
        boolean referenced = false;
        try {
            sourcePath = materializeSource(version);
            DocumentSourceFormat format = sourceFormat(version.getSourceFormat());
            DocumentFormatParser parser = parsers.stream()
                    .filter(candidate -> candidate.supports(format))
                    .findFirst()
                    .orElseThrow(() -> permanent(
                            "DOCUMENT_FORMAT_MISMATCH",
                            "No parser is registered for source format"
                    ));
            ParsedDocument parsed = parser.parse(new DocumentFormatParser.ParseSource(
                    sourcePath,
                    version.getId(),
                    document.getName(),
                    format,
                    version.getSourceSha256()
            ));
            CanonicalDocument canonical = assembler.assemble(
                    version.getId(),
                    document.getName(),
                    format,
                    version.getSourceSha256(),
                    parsed,
                    Instant.now()
            );
            validator.validate(canonical);
            written = writer.write(canonical);

            objectKey = newCanonicalKey(version);
            CanonicalArtifactStore.WriteResult stored;
            try (InputStream input = Files.newInputStream(written.path())) {
                stored = canonicalArtifactStore.put(
                        objectKey,
                        input,
                        written.sizeBytes(),
                        properties.getMaxCanonicalBytes()
                );
            }
            if (stored.sizeBytes() != written.sizeBytes()
                    || !stored.sha256().equals(written.sha256())) {
                throw permanent(
                        "CANONICAL_VALIDATION_FAILED",
                        "Stored canonical object does not match local artifact"
                );
            }
            verifyObject(objectKey, stored.sizeBytes(), stored.sha256());

            DocumentCanonicalArtifactEntity artifact = entity(
                    version,
                    canonical,
                    objectKey,
                    stored
            );
            try {
                if (artifactMapper.insert(artifact) != 1) {
                    throw retryable(
                            "PROCESSING_DATABASE_UNAVAILABLE",
                            "Canonical artifact manifest was not inserted",
                            null
                    );
                }
                referenced = true;
                return artifact;
            } catch (DataIntegrityViolationException duplicate) {
                DocumentCanonicalArtifactEntity winner = artifactMapper
                        .findByDocumentVersionId(documentVersionId);
                if (winner == null) {
                    throw duplicate;
                }
                deleteOwnObjectQuietly(objectKey);
                objectKey = null;
                verifyExisting(winner);
                return winner;
            }
        } catch (ObjectStorageException exception) {
            if (exception.reason() == ObjectStorageException.Reason.FILE_TOO_LARGE) {
                throw permanent(
                        "DOCUMENT_PARSE_LIMIT_EXCEEDED",
                        "Canonical object exceeds configured limit"
                );
            }
            throw retryable(
                    objectKey == null
                            ? "SOURCE_OBJECT_UNAVAILABLE"
                            : "CANONICAL_OBJECT_UNAVAILABLE",
                    "Document object storage is temporarily unavailable",
                    exception
            );
        } catch (IOException exception) {
            throw retryable(
                    "CANONICAL_OBJECT_UNAVAILABLE",
                    "Canonical temporary file could not be read",
                    exception
            );
        } finally {
            if (objectKey != null && !referenced) {
                deleteOwnObjectQuietly(objectKey);
            }
            deleteTemp(sourcePath);
            deleteTemp(written == null ? null : written.path());
        }
    }

    /** 下载原文件时同步校验 N2.2 保存的大小与 SHA-256，不信任对象引用本身。 */
    private Path materializeSource(DocumentVersionEntity version) {
        if (!sourceObjectStore.exists(version.getSourceObjectKey())) {
            throw permanent("SOURCE_OBJECT_MISSING", "Source object does not exist");
        }
        Path path = null;
        try {
            path = Files.createTempFile("docquery-source-", ".bin");
            MessageDigest digest = sha256Digest();
            long count = 0;
            try (InputStream input = sourceObjectStore.open(version.getSourceObjectKey());
                 OutputStream output = Files.newOutputStream(path)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    count += read;
                    if (count > version.getSourceSizeBytes()) {
                        throw permanent(
                                "SOURCE_OBJECT_INTEGRITY_MISMATCH",
                                "Source object size does not match accepted facts"
                        );
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (count != version.getSourceSizeBytes()
                    || !sha256.equals(version.getSourceSha256())) {
                throw permanent(
                        "SOURCE_OBJECT_INTEGRITY_MISMATCH",
                        "Source object does not match accepted facts"
                );
            }
            return path;
        } catch (DocumentParseException exception) {
            deleteTemp(path);
            throw exception;
        } catch (ObjectStorageException exception) {
            deleteTemp(path);
            throw exception;
        } catch (IOException exception) {
            deleteTemp(path);
            throw retryable(
                    "SOURCE_OBJECT_UNAVAILABLE",
                    "Source object could not be materialized",
                    exception
            );
        }
    }

    private void verifyExisting(DocumentCanonicalArtifactEntity artifact) {
        try {
            if (!canonicalArtifactStore.bucketName().equals(artifact.getCanonicalBucket())
                    || !canonicalArtifactStore.exists(artifact.getCanonicalObjectKey())) {
                throw retryable(
                        "CANONICAL_OBJECT_UNAVAILABLE",
                        "Canonical object is not available",
                        null
                );
            }
            verifyObject(
                    artifact.getCanonicalObjectKey(),
                    artifact.getCanonicalSizeBytes(),
                    artifact.getCanonicalSha256()
            );
        } catch (ObjectStorageException exception) {
            throw retryable(
                    "CANONICAL_OBJECT_UNAVAILABLE",
                    "Canonical object is not available",
                    exception
            );
        }
    }

    private void verifyObject(String objectKey, long expectedSize, String expectedSha256) {
        MessageDigest digest = sha256Digest();
        long count = 0;
        try (InputStream input = canonicalArtifactStore.open(objectKey)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                count += read;
                if (count > expectedSize) {
                    break;
                }
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw retryable(
                    "CANONICAL_OBJECT_UNAVAILABLE",
                    "Canonical object could not be verified",
                    exception
            );
        }
        if (count != expectedSize
                || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
            throw permanent(
                    "CANONICAL_VALIDATION_FAILED",
                    "Canonical object checksum is invalid"
            );
        }
    }

    private DocumentCanonicalArtifactEntity entity(
            DocumentVersionEntity version,
            CanonicalDocument canonical,
            String objectKey,
            CanonicalArtifactStore.WriteResult stored
    ) {
        DocumentCanonicalArtifactEntity artifact = new DocumentCanonicalArtifactEntity();
        artifact.setTenantId(version.getTenantId());
        artifact.setDocumentVersionId(version.getId());
        artifact.setSchemaVersion(canonical.schemaVersion());
        artifact.setSourceFormat(version.getSourceFormat());
        artifact.setParserName(canonical.parserName());
        artifact.setParserVersion(canonical.parserVersion());
        artifact.setCanonicalBucket(canonicalArtifactStore.bucketName());
        artifact.setCanonicalObjectKey(objectKey);
        artifact.setCanonicalSizeBytes(stored.sizeBytes());
        artifact.setCanonicalSha256(stored.sha256());
        artifact.setCanonicalTextSha256(canonical.canonicalTextSha256());
        artifact.setTextLength(canonical.textLength());
        artifact.setBlockCount(canonical.blocks().size());
        artifact.setHeadingCount(canonical.headings().size());
        artifact.setWarningCount(canonical.warnings().size());
        artifact.setPageCount(canonical.pageCount());
        artifact.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return artifact;
    }

    private String newCanonicalKey(DocumentVersionEntity version) {
        String prefix = properties.getCanonicalPrefix();
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix
                + version.getTenantId() + "/"
                + version.getId() + "/v"
                + properties.getSchemaVersion() + "/"
                + UUID.randomUUID() + ".jsonl";
    }

    private DocumentSourceFormat sourceFormat(String code) {
        try {
            return DocumentSourceFormat.fromCode(code);
        } catch (IllegalArgumentException exception) {
            throw permanent("DOCUMENT_FORMAT_MISMATCH", "Source format code is invalid");
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteOwnObjectQuietly(String objectKey) {
        try {
            canonicalArtifactStore.delete(objectKey);
        } catch (RuntimeException exception) {
            // 不输出 Key；宽限期回收器会再次依据 MySQL 引用检查。
            LOG.warn("Unreferenced canonical object could not be deleted immediately");
        }
    }

    private void deleteTemp(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOG.warn("Document parsing temporary file could not be deleted immediately");
        }
    }

    private DocumentParseException permanent(String code, String message) {
        return new DocumentParseException(code, message, false);
    }

    private DocumentParseException retryable(String code, String message, Throwable cause) {
        return new DocumentParseException(code, message, true, cause);
    }
}
