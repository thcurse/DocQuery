package com.doc.docquery.service.impl;

import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.dto.CreateDocumentUploadDTO;
import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.dto.CreateDocumentVersionDTO;
import com.doc.docquery.dto.DocumentRebuildSourceDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadAcceptanceService;
import com.doc.docquery.service.DocumentUploadCoordinator;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/**
 * 先持久保存原文件，再调用 N2.1 原子受理事务。
 *
 * <p>对象存储与 MySQL 无分布式事务，因此仅在受理前失败或确认无引用时清理本次
 * 随机 Key；提交结果不确定时保留对象，交由延迟回收处理。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DocumentUploadCoordinatorImpl implements DocumentUploadCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(
            DocumentUploadCoordinatorImpl.class
    );

    private final SourceObjectStore objectStore;
    private final ObjectStorageProperties properties;
    private final DocumentUploadAcceptanceService acceptanceService;
    private final DocumentVersionMapper documentVersionMapper;

    public DocumentUploadCoordinatorImpl(
            SourceObjectStore objectStore,
            ObjectStorageProperties properties,
            DocumentUploadAcceptanceService acceptanceService,
            DocumentVersionMapper documentVersionMapper
    ) {
        this.objectStore = objectStore;
        this.properties = properties;
        this.acceptanceService = acceptanceService;
        this.documentVersionMapper = documentVersionMapper;
    }

    @Override
    public DocumentUploadAcceptedVO uploadNewDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            String idempotencyKey,
            CreateDocumentUploadMetadataDTO metadata,
            MultipartFile file
    ) {
        acceptanceService.validateNewDocumentScope(principal, tenantId, knowledgeBaseId);
        if (metadata == null) {
            throw invalidFile("Document upload metadata is required");
        }
        CreateDocumentUploadDTO request = new CreateDocumentUploadDTO();
        request.setDocumentName(metadata.getDocumentName());
        request.setIdempotencyKey(validateIdempotencyKey(idempotencyKey));
        return storeAndAccept(
                tenantId,
                file,
                source -> acceptanceService.acceptNewDocument(
                        principal,
                        tenantId,
                        knowledgeBaseId,
                        request,
                        source
                )
        );
    }

    @Override
    public DocumentUploadAcceptedVO uploadNewVersion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey,
            MultipartFile file
    ) {
        acceptanceService.validateNewVersionScope(
                principal,
                tenantId,
                knowledgeBaseId,
                documentId
        );
        CreateDocumentVersionDTO request = new CreateDocumentVersionDTO();
        request.setIdempotencyKey(validateIdempotencyKey(idempotencyKey));
        return storeAndAccept(
                tenantId,
                file,
                source -> acceptanceService.acceptNewVersion(
                        principal,
                        tenantId,
                        knowledgeBaseId,
                        documentId,
                        request,
                        source
                )
        );
    }

    @Override
    public DocumentUploadAcceptedVO rebuildDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    ) {
        String key = validateIdempotencyKey(idempotencyKey);
        DocumentUploadAcceptedVO replay = acceptanceService.findRebuildReplay(
                principal, tenantId, knowledgeBaseId, documentId, key
        );
        if (replay != null) {
            return replay;
        }
        DocumentRebuildSourceDTO source = acceptanceService.loadRebuildSource(
                principal, tenantId, knowledgeBaseId, documentId
        );
        if (!objectStore.bucketName().equals(source.sourceBucket())) {
            throw rebuildSourceUnavailable();
        }

        String objectKey = newObjectKey(tenantId);
        boolean objectWriteAttempted = false;
        StoredSourceObjectDTO copied;
        try (InputStream input = objectStore.open(source.sourceObjectKey())) {
            objectWriteAttempted = true;
            SourceObjectStore.WriteResult result = objectStore.put(
                    objectKey,
                    input,
                    source.sourceSizeBytes(),
                    properties.getUploadMaxBytes(),
                    source.sourceContentType()
            );
            if (result.sizeBytes() != source.sourceSizeBytes()
                    || !result.sha256().equals(source.sourceSha256())) {
                throw rebuildSourceUnavailable();
            }
            copied = new StoredSourceObjectDTO();
            copied.setOriginalFilename(source.originalFilename());
            copied.setSourceFormat(source.sourceFormat());
            copied.setSourceBucket(objectStore.bucketName());
            copied.setSourceObjectKey(objectKey);
            copied.setSourceSizeBytes(result.sizeBytes());
            copied.setSourceSha256(result.sha256());
            copied.setSourceContentType(source.sourceContentType());
        } catch (BusinessException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "rejected rebuild object");
            }
            throw exception;
        } catch (ObjectStorageException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "failed rebuild object");
            }
            throw mapStorageFailure(exception);
        } catch (IOException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "unreadable rebuild object");
            }
            throw unavailable();
        } catch (RuntimeException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "unexpected failed rebuild object");
            }
            throw exception;
        }
        // 先完成输入流关闭，再开始受理，避免关闭失败补偿已提交的原文件。
        return acceptStoredSource(copied, stored -> acceptanceService.acceptRebuild(
                principal,
                tenantId,
                knowledgeBaseId,
                documentId,
                source.sourceVersionId(),
                key,
                stored
        ));
    }

    private DocumentUploadAcceptedVO storeAndAccept(
            long tenantId,
            MultipartFile file,
            AcceptanceOperation operation
    ) {
        ValidatedFile validated = validateFile(file);
        String objectKey = newObjectKey(tenantId);
        boolean objectWriteAttempted = false;
        StoredSourceObjectDTO source;
        try (InputStream input = file.getInputStream()) {
            // Key 是本次调用独占的随机值，因此即使 PUT 中途失败也可安全精确删除。
            objectWriteAttempted = true;
            SourceObjectStore.WriteResult result = objectStore.put(
                    objectKey,
                    input,
                    validated.sizeBytes(),
                    properties.getUploadMaxBytes(),
                    validated.contentType()
            );
            source = toStoredSource(
                    validated,
                    objectKey,
                    result
            );
        } catch (BusinessException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "rejected upload object");
            }
            throw exception;
        } catch (ObjectStorageException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "failed upload object");
            }
            throw mapStorageFailure(exception);
        } catch (IOException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "unreadable upload object");
            }
            throw unavailable();
        } catch (RuntimeException exception) {
            if (objectWriteAttempted) {
                safeDelete(objectKey, "unexpected failed upload object");
            }
            throw exception;
        }
        return acceptStoredSource(source, operation);
    }

    private DocumentUploadAcceptedVO acceptStoredSource(
            StoredSourceObjectDTO source,
            AcceptanceOperation operation
    ) {
        DocumentUploadAcceptedVO accepted;
        try {
            accepted = operation.accept(source);
        } catch (BusinessException exception) {
            // 业务拒绝已回滚；仍检查引用，保护对象 Key 冲突等已存在引用的情况。
            deleteIfUnreferenced(source, "rejected source object");
            throw exception;
        }
        // 其他受理异常可能发生在提交时，不能凭异常或即时查询判断事务未提交。
        // 合法幂等重放会返回旧版本；仅清理确认未被数据库引用的本次随机对象。
        deleteIfUnreferenced(source, "unreferenced replay object");
        return accepted;
    }

    private void deleteIfUnreferenced(StoredSourceObjectDTO source, String reason) {
        try {
            if (documentVersionMapper.countBySourceObject(
                    source.getSourceBucket(), source.getSourceObjectKey()
            ) == 0) {
                safeDelete(source.getSourceObjectKey(), reason);
            }
        } catch (RuntimeException lookupFailure) {
            // 查询失败不改变已完成的受理结果，也不能据此删除可能被引用的原文件。
            LOG.warn("Source object cleanup deferred: reference lookup failed ({})", reason);
        }
    }

    private ValidatedFile validateFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() < 1) {
            throw invalidFile("Upload file must not be empty");
        }
        if (file.getSize() > properties.getUploadMaxBytes()) {
            throw tooLarge();
        }
        String filename = normalizeFilename(file.getOriginalFilename());
        String format = formatOf(filename);
        String contentType = normalizeContentType(file.getContentType());
        return new ValidatedFile(filename, format, file.getSize(), contentType);
    }

    private String normalizeFilename(String requested) {
        if (requested == null) {
            throw invalidFile("Original filename is required");
        }
        String filename = requested.strip();
        int length = filename.codePointCount(0, filename.length());
        if (length < 1
                || length > 512
                || filename.equals(".")
                || filename.equals("..")
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidFile("Original filename is invalid");
        }
        return filename;
    }

    private String formatOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return DocumentSourceFormat.PDF.getCode();
        }
        if (lower.endsWith(".docx")) {
            return DocumentSourceFormat.DOCX.getCode();
        }
        if (lower.endsWith(".txt")) {
            return DocumentSourceFormat.TXT.getCode();
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return DocumentSourceFormat.MARKDOWN.getCode();
        }
        throw new BusinessException(
                BusinessException.Failure.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_DOCUMENT_FORMAT",
                "Document format is not supported"
        );
    }

    private String normalizeContentType(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String value = requested.strip();
        if (value.length() > 255
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw invalidFile("Upload Content-Type is invalid");
        }
        return value;
    }

    private String validateIdempotencyKey(String requested) {
        if (requested == null
                || requested.isEmpty()
                || requested.length() > 128
                || requested.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw invalidFile(
                    "Idempotency-Key must contain 1 to 128 visible ASCII characters"
            );
        }
        return requested;
    }

    private String newObjectKey(long tenantId) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return "source/%d/%04d/%02d/%s".formatted(
                tenantId,
                date.getYear(),
                date.getMonthValue(),
                UUID.randomUUID()
        );
    }

    private StoredSourceObjectDTO toStoredSource(
            ValidatedFile file,
            String objectKey,
            SourceObjectStore.WriteResult result
    ) {
        StoredSourceObjectDTO source = new StoredSourceObjectDTO();
        source.setOriginalFilename(file.filename());
        source.setSourceFormat(file.format());
        source.setSourceBucket(objectStore.bucketName());
        source.setSourceObjectKey(objectKey);
        source.setSourceSizeBytes(result.sizeBytes());
        source.setSourceSha256(result.sha256());
        source.setSourceContentType(file.contentType());
        return source;
    }

    private void safeDelete(String objectKey, String reason) {
        try {
            objectStore.delete(objectKey);
        } catch (RuntimeException cleanupFailure) {
            // 只记录随机 Key 的删除失败，不记录文件名、正文、摘要或凭证。
            LOG.warn("Source object cleanup failed ({})", reason);
        }
    }

    private BusinessException mapStorageFailure(ObjectStorageException exception) {
        if (exception.reason() == ObjectStorageException.Reason.FILE_TOO_LARGE) {
            return tooLarge();
        }
        return unavailable();
    }

    private BusinessException invalidFile(String message) {
        return new BusinessException(
                BusinessException.Failure.VALIDATION,
                "INVALID_UPLOAD_FILE",
                message
        );
    }

    private BusinessException tooLarge() {
        return new BusinessException(
                BusinessException.Failure.TOO_LARGE,
                "FILE_TOO_LARGE",
                "Uploaded file exceeds size limit"
        );
    }

    private BusinessException unavailable() {
        return new BusinessException(
                BusinessException.Failure.UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE",
                "Source object storage is unavailable"
        );
    }

    private BusinessException rebuildSourceUnavailable() {
        return new BusinessException(
                BusinessException.Failure.UNAVAILABLE,
                "DOCUMENT_REBUILD_SOURCE_UNAVAILABLE",
                "The active version source could not be copied safely"
        );
    }

    @FunctionalInterface
    private interface AcceptanceOperation {
        DocumentUploadAcceptedVO accept(StoredSourceObjectDTO source);
    }

    private record ValidatedFile(
            String filename,
            String format,
            long sizeBytes,
            String contentType
    ) {
    }
}
