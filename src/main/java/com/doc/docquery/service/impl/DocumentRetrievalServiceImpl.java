package com.doc.docquery.service.impl;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentRetrievalArtifactMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.retrieval.CanonicalArtifactReader;
import com.doc.docquery.retrieval.RetrievalArtifact;
import com.doc.docquery.retrieval.RetrievalArtifactGenerator;
import com.doc.docquery.retrieval.RetrievalArtifactValidator;
import com.doc.docquery.retrieval.RetrievalGenerationFingerprint;
import com.doc.docquery.retrieval.RetrievalJsonlReader;
import com.doc.docquery.retrieval.RetrievalJsonlWriter;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentRetrievalService;
import com.doc.docquery.service.NavigationEmbeddingGateway;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.RetrievalArtifactStore;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * N2.4 持久化编排：读取可信 canonical、生成完整检索对象、上传校验后登记清单。
 * 本服务刻意不修改 DocumentVersion、ProcessingJob 或 activeVersion。
 */
@Service
@ConditionalOnProperty(
        prefix = "docquery.retrieval",
        name = "provider-enabled",
        havingValue = "true"
)
public class DocumentRetrievalServiceImpl implements DocumentRetrievalService {

    private static final Logger LOG = LoggerFactory.getLogger(
            DocumentRetrievalServiceImpl.class
    );

    private final DocumentVersionMapper versionMapper;
    private final DocumentMapper documentMapper;
    private final DocumentCanonicalArtifactMapper canonicalMapper;
    private final DocumentRetrievalArtifactMapper retrievalMapper;
    private final CanonicalArtifactStore canonicalStore;
    private final RetrievalArtifactStore retrievalStore;
    private final CanonicalArtifactReader canonicalReader;
    private final RetrievalArtifactGenerator generator;
    private final RetrievalArtifactValidator validator;
    private final RetrievalJsonlWriter writer;
    private final RetrievalJsonlReader retrievalReader;
    private final RetrievalGenerationFingerprint fingerprint;
    private final DocumentRetrievalProperties properties;

    public DocumentRetrievalServiceImpl(
            DocumentVersionMapper versionMapper,
            DocumentMapper documentMapper,
            DocumentCanonicalArtifactMapper canonicalMapper,
            DocumentRetrievalArtifactMapper retrievalMapper,
            CanonicalArtifactStore canonicalStore,
            RetrievalArtifactStore retrievalStore,
            CanonicalArtifactReader canonicalReader,
            RetrievalArtifactGenerator generator,
            RetrievalArtifactValidator validator,
            RetrievalJsonlWriter writer,
            RetrievalJsonlReader retrievalReader,
            RetrievalGenerationFingerprint fingerprint,
            DocumentRetrievalProperties properties
    ) {
        this.versionMapper = versionMapper;
        this.documentMapper = documentMapper;
        this.canonicalMapper = canonicalMapper;
        this.retrievalMapper = retrievalMapper;
        this.canonicalStore = canonicalStore;
        this.retrievalStore = retrievalStore;
        this.canonicalReader = canonicalReader;
        this.generator = generator;
        this.validator = validator;
        this.writer = writer;
        this.retrievalReader = retrievalReader;
        this.fingerprint = fingerprint;
        this.properties = properties;
    }

    @Override
    public DocumentRetrievalArtifactEntity ensureRetrieval(long documentVersionId) {
        try {
            return ensureInternal(documentVersionId);
        } catch (RetrievalGenerationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw retryable(
                    "PROCESSING_DATABASE_UNAVAILABLE",
                    "Retrieval artifact database operation is temporarily unavailable",
                    exception
            );
        }
    }

    private DocumentRetrievalArtifactEntity ensureInternal(long documentVersionId) {
        if (documentVersionId < 1) {
            throw permanent("CANONICAL_ARTIFACT_NOT_FOUND", "Document version is invalid");
        }
        DocumentCanonicalArtifactEntity canonical = canonicalMapper
                .findByDocumentVersionId(documentVersionId);
        if (canonical == null) {
            throw permanent(
                    "CANONICAL_ARTIFACT_NOT_FOUND",
                    "Canonical artifact manifest does not exist"
            );
        }
        DocumentRetrievalArtifactEntity existing = retrievalMapper
                .findByDocumentVersionId(documentVersionId);
        if (existing != null) {
            verifyExisting(existing, canonical);
            return existing;
        }

        DocumentVersionEntity version = versionMapper.findById(documentVersionId);
        DocumentEntity document = version == null ? null : documentMapper.findByTenantAndId(
                version.getTenantId(),
                version.getDocumentId()
        );
        if (version == null || document == null
                || !version.getTenantId().equals(canonical.getTenantId())) {
            throw permanent(
                    "CANONICAL_ARTIFACT_INTEGRITY_MISMATCH",
                    "Canonical artifact facts do not match the document version"
            );
        }

        String objectKey = null;
        boolean referenced = false;
        RetrievalJsonlWriter.WrittenArtifact written = null;
        try {
            CanonicalDocument canonicalDocument = readCanonical(canonical);
            if (canonicalDocument.documentVersionId() != documentVersionId) {
                throw permanent(
                        "CANONICAL_ARTIFACT_INTEGRITY_MISMATCH",
                        "Canonical document version does not match manifest"
                );
            }
            RetrievalArtifact artifact = generator.generate(
                    canonicalDocument,
                    document.getName(),
                    canonical.getCanonicalSha256()
            );
            RetrievalArtifactValidator.ValidationResult validation = validator
                    .validate(artifact);
            written = writer.write(artifact, validation);
            objectKey = newObjectKey(version);

            RetrievalArtifactStore.WriteResult stored;
            try (InputStream input = Files.newInputStream(written.path())) {
                stored = retrievalStore.put(
                        objectKey,
                        input,
                        written.sizeBytes(),
                        properties.getMaxRetrievalBytes()
                );
            }
            if (stored.sizeBytes() != written.sizeBytes()
                    || !stored.sha256().equals(written.sha256())) {
                throw permanent(
                        "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                        "Stored retrieval object does not match local artifact"
                );
            }
            verifyStoredObject(objectKey, stored.sizeBytes(), stored.sha256());

            DocumentRetrievalArtifactEntity entity = entity(
                    version,
                    canonical,
                    artifact,
                    written,
                    objectKey
            );
            try {
                if (retrievalMapper.insert(entity) != 1) {
                    throw retryable(
                            "PROCESSING_DATABASE_UNAVAILABLE",
                            "Retrieval artifact manifest was not inserted",
                            null
                    );
                }
                referenced = true;
                return entity;
            } catch (DataIntegrityViolationException duplicate) {
                DocumentRetrievalArtifactEntity winner = retrievalMapper
                        .findByDocumentVersionId(documentVersionId);
                if (winner == null) {
                    throw duplicate;
                }
                deleteOwnObjectQuietly(objectKey);
                objectKey = null;
                verifyExisting(winner, canonical);
                return winner;
            }
        } catch (ObjectStorageException exception) {
            if (exception.reason() == ObjectStorageException.Reason.FILE_TOO_LARGE) {
                throw permanent(
                        "RETRIEVAL_GENERATION_LIMIT_EXCEEDED",
                        "Retrieval object exceeds configured limit"
                );
            }
            throw retryable(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Document object storage is temporarily unavailable",
                    exception
            );
        } catch (IOException exception) {
            throw retryable(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Retrieval temporary file could not be read",
                    exception
            );
        } finally {
            if (objectKey != null && !referenced) {
                deleteOwnObjectQuietly(objectKey);
            }
            deleteTemp(written == null ? null : written.path());
        }
    }

    private CanonicalDocument readCanonical(DocumentCanonicalArtifactEntity manifest) {
        if (!canonicalStore.bucketName().equals(manifest.getCanonicalBucket())
                || !canonicalStore.exists(manifest.getCanonicalObjectKey())) {
            throw permanent(
                    "CANONICAL_ARTIFACT_NOT_FOUND",
                    "Canonical artifact object does not exist"
            );
        }
        try (MeasuredInputStream input = new MeasuredInputStream(
                canonicalStore.open(manifest.getCanonicalObjectKey())
        )) {
            CanonicalDocument document = canonicalReader.read(input);
            if (input.count() != manifest.getCanonicalSizeBytes()
                    || !input.sha256().equals(manifest.getCanonicalSha256())
                    || !document.canonicalTextSha256().equals(
                    manifest.getCanonicalTextSha256())) {
                throw permanent(
                        "CANONICAL_ARTIFACT_INTEGRITY_MISMATCH",
                        "Canonical artifact checksum does not match manifest"
                );
            }
            return document;
        } catch (IOException exception) {
            throw retryable(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Canonical artifact could not be read",
                    exception
            );
        }
    }

    private void verifyExisting(
            DocumentRetrievalArtifactEntity existing,
            DocumentCanonicalArtifactEntity canonical
    ) {
        String expectedFingerprint = fingerprint.calculate(canonical.getCanonicalSha256());
        if (!expectedFingerprint.equals(existing.getGenerationFingerprint())
                || !canonical.getId().equals(existing.getCanonicalArtifactId())) {
            throw permanent(
                    "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                    "Existing retrieval artifact uses a different generation configuration"
            );
        }
        if (!retrievalStore.bucketName().equals(existing.getRetrievalBucket())
                || !retrievalStore.exists(existing.getRetrievalObjectKey())) {
            throw retryable(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Retrieval artifact object is unavailable",
                    null
            );
        }
        try (MeasuredInputStream input = new MeasuredInputStream(
                retrievalStore.open(existing.getRetrievalObjectKey())
        )) {
            RetrievalJsonlReader.ReadArtifact read = retrievalReader.read(input);
            if (input.count() != existing.getRetrievalSizeBytes()
                    || !input.sha256().equals(existing.getRetrievalSha256())
                    || !read.validation().semanticSha256().equals(existing.getSemanticSha256())
                    || !read.validation().vectorSha256().equals(existing.getVectorSha256())
                    || !read.artifact().generationFingerprint().equals(expectedFingerprint)
                    || !read.artifact().canonicalSha256().equals(canonical.getCanonicalSha256())
                    || read.artifact().nodes().size() != existing.getNodeCount()) {
                throw permanent(
                        "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                        "Existing retrieval artifact does not match manifest"
                );
            }
        } catch (IOException exception) {
            throw retryable(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Retrieval artifact could not be read",
                    exception
            );
        }
    }

    private void verifyStoredObject(String key, long size, String sha256) {
        try (MeasuredInputStream input = new MeasuredInputStream(retrievalStore.open(key))) {
            RetrievalJsonlReader.ReadArtifact ignored = retrievalReader.read(input);
            if (input.count() != size || !input.sha256().equals(sha256)) {
                throw permanent(
                        "RETRIEVAL_ARTIFACT_VALIDATION_FAILED",
                        "Stored retrieval object checksum is invalid"
                );
            }
        } catch (IOException exception) {
            throw retryable(
                    "RETRIEVAL_OBJECT_UNAVAILABLE",
                    "Stored retrieval object could not be verified",
                    exception
            );
        }
    }

    private DocumentRetrievalArtifactEntity entity(
            DocumentVersionEntity version,
            DocumentCanonicalArtifactEntity canonical,
            RetrievalArtifact artifact,
            RetrievalJsonlWriter.WrittenArtifact written,
            String objectKey
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DocumentRetrievalArtifactEntity entity = new DocumentRetrievalArtifactEntity();
        entity.setTenantId(version.getTenantId());
        entity.setDocumentVersionId(version.getId());
        entity.setCanonicalArtifactId(canonical.getId());
        entity.setSchemaVersion(artifact.schemaVersion());
        entity.setChatProvider("DEEPSEEK");
        entity.setChatModel(artifact.chatModel());
        entity.setChatPromptVersion(artifact.chatPromptVersion());
        entity.setThinkingMode("DISABLED");
        entity.setEmbeddingProvider("ALIBABA_MODEL_STUDIO");
        entity.setEmbeddingModel(artifact.embeddingModel());
        entity.setEmbeddingDimension(artifact.embeddingDimension());
        entity.setEmbeddingTemplateVersion(artifact.embeddingTemplateVersion());
        entity.setGenerationFingerprint(artifact.generationFingerprint());
        entity.setRetrievalBucket(retrievalStore.bucketName());
        entity.setRetrievalObjectKey(objectKey);
        entity.setRetrievalSizeBytes(written.sizeBytes());
        entity.setRetrievalSha256(written.sha256());
        entity.setSemanticSha256(written.semanticSha256());
        entity.setVectorSha256(written.vectorSha256());
        entity.setProfileCount(1);
        entity.setNodeCount(artifact.nodes().size());
        entity.setVectorCount(artifact.nodes().size() + 1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String newObjectKey(DocumentVersionEntity version) {
        String prefix = properties.getRetrievalPrefix();
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix + version.getTenantId() + "/" + version.getId()
                + "/v" + properties.getSchemaVersion() + "/"
                + UUID.randomUUID() + ".jsonl";
    }

    private void deleteOwnObjectQuietly(String key) {
        try {
            retrievalStore.delete(key);
        } catch (RuntimeException exception) {
            LOG.warn("Unreferenced retrieval object could not be deleted immediately");
        }
    }

    private void deleteTemp(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOG.warn("Retrieval temporary file could not be deleted immediately");
        }
    }

    private RetrievalGenerationException permanent(String code, String message) {
        return new RetrievalGenerationException(code, message, false);
    }

    private RetrievalGenerationException retryable(
            String code,
            String message,
            Throwable cause
    ) {
        return new RetrievalGenerationException(code, message, true, cause);
    }

    /** 调用方读到 EOF 后才能取得最终字节数和 SHA-256。 */
    private static final class MeasuredInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;

        private MeasuredInputStream(InputStream input) {
            super(input);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
                digest.update((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                digest.update(buffer, offset, read);
            }
            return read;
        }

        private long count() {
            return count;
        }

        private String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
