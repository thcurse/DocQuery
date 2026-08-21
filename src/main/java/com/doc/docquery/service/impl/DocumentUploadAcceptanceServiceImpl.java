package com.doc.docquery.service.impl;

import com.doc.docquery.dto.CreateDocumentUploadDTO;
import com.doc.docquery.dto.CreateDocumentVersionDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import com.doc.docquery.entity.OutboxEventEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.DocumentSourceFormat;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.enums.OutboxEventStatus;
import com.doc.docquery.enums.OutboxEventType;
import com.doc.docquery.enums.ProcessingJobStatus;
import com.doc.docquery.enums.ProcessingJobType;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.mapper.KnowledgeBaseMapper;
import com.doc.docquery.mapper.OutboxEventMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadAcceptanceService;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/**
 * N2.1 文档上传数据库受理实现。
 *
 * <p>在一个事务中创建逻辑文档/版本、首次处理任务和 Outbox 事件；通过
 * Tenant 范围幂等键、规范化请求指纹、Document 行锁和数据库唯一约束处理
 * 重放与并发。本实现只消费已持久化对象描述，不连接对象存储、MQ 或解析器。</p>
 */
@Service
public class DocumentUploadAcceptanceServiceImpl
        implements DocumentUploadAcceptanceService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String TENANT_ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String DOCUMENT_ACTIVE = DocumentStatus.ACTIVE.getCode();
    private static final String VERSION_PROCESSING =
            DocumentVersionStatus.PROCESSING.getCode();
    private static final String VERSION_READY = DocumentVersionStatus.READY.getCode();
    private static final String VERSION_FAILED = DocumentVersionStatus.FAILED.getCode();
    private static final String JOB_PENDING = ProcessingJobStatus.PENDING.getCode();
    private static final String JOB_TYPE_INGEST = ProcessingJobType.INGEST.getCode();
    private static final String OUTBOX_PENDING = OutboxEventStatus.PENDING.getCode();
    private static final String PROCESS_REQUESTED =
            OutboxEventType.DOCUMENT_VERSION_PROCESS_REQUESTED.getCode();

    private final TenantMapper tenantMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ProcessingJobMapper processingJobMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public DocumentUploadAcceptanceServiceImpl(
            TenantMapper tenantMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ProcessingJobMapper processingJobMapper,
            OutboxEventMapper outboxEventMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.tenantMapper = tenantMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.processingJobMapper = processingJobMapper;
        this.outboxEventMapper = outboxEventMapper;

        // 显式事务模板允许唯一约束冲突先完整回滚，再开启独立只读事务解析赢家。
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public void validateNewDocumentScope(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId
    ) {
        requirePositiveId(tenantId, "Tenant ID must be positive");
        requirePositiveId(knowledgeBaseId, "KnowledgeBase ID must be positive");
        requireActiveTenantAndKnowledgeBase(principal, tenantId, knowledgeBaseId);
    }

    @Override
    public void validateNewVersionScope(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId
    ) {
        validateNewDocumentScope(principal, tenantId, knowledgeBaseId);
        requirePositiveId(documentId, "Document ID must be positive");
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndId(
                tenantId,
                knowledgeBaseId,
                documentId
        );
        if (document == null || !DOCUMENT_ACTIVE.equals(document.getStatus())) {
            throw documentNotFound();
        }
    }

    @Override
    public DocumentUploadAcceptedVO acceptNewDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            CreateDocumentUploadDTO dto,
            StoredSourceObjectDTO source
    ) {
        requirePositiveId(tenantId, "Tenant ID must be positive");
        requirePositiveId(knowledgeBaseId, "KnowledgeBase ID must be positive");
        if (dto == null) {
            throw validation("Document upload metadata is required");
        }
        String documentName = normalizeDocumentName(dto.getDocumentName());
        String idempotencyKey = validateIdempotencyKey(dto.getIdempotencyKey());
        StoredSourceObjectDTO normalizedSource = normalizeSource(source);
        String idempotencyHash = sha256(idempotencyKey);
        // 指纹覆盖会改变业务结果的规范化字段，Bucket/Object Key 由独立唯一约束保护。
        String fingerprint = fingerprint(
                "CREATE_DOCUMENT",
                Long.toString(tenantId),
                Long.toString(knowledgeBaseId),
                documentName,
                normalizedSource.getOriginalFilename(),
                normalizedSource.getSourceFormat(),
                Long.toString(normalizedSource.getSourceSizeBytes()),
                normalizedSource.getSourceSha256()
        );

        try {
            return executeWrite(() -> acceptNewDocumentTransaction(
                    principal,
                    tenantId,
                    knowledgeBaseId,
                    documentName,
                    idempotencyHash,
                    fingerprint,
                    normalizedSource
            ));
        } catch (DuplicateKeyException exception) {
            // 并发请求可能同时通过前置查询；数据库约束决定赢家后重建幂等响应。
            return resolveDuplicateWrite(
                    tenantId,
                    knowledgeBaseId,
                    documentName,
                    idempotencyHash,
                    fingerprint,
                    normalizedSource
            );
        }
    }

    @Override
    public DocumentUploadAcceptedVO acceptNewVersion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            CreateDocumentVersionDTO dto,
            StoredSourceObjectDTO source
    ) {
        requirePositiveId(tenantId, "Tenant ID must be positive");
        requirePositiveId(knowledgeBaseId, "KnowledgeBase ID must be positive");
        requirePositiveId(documentId, "Document ID must be positive");
        if (dto == null) {
            throw validation("Document version metadata is required");
        }
        String idempotencyKey = validateIdempotencyKey(dto.getIdempotencyKey());
        StoredSourceObjectDTO normalizedSource = normalizeSource(source);
        String idempotencyHash = sha256(idempotencyKey);
        // 新版本指纹包含稳定 Document ID，不依赖可变文档名称。
        String fingerprint = fingerprint(
                "CREATE_VERSION",
                Long.toString(tenantId),
                Long.toString(knowledgeBaseId),
                Long.toString(documentId),
                normalizedSource.getOriginalFilename(),
                normalizedSource.getSourceFormat(),
                Long.toString(normalizedSource.getSourceSizeBytes()),
                normalizedSource.getSourceSha256()
        );

        try {
            return executeWrite(() -> acceptNewVersionTransaction(
                    principal,
                    tenantId,
                    knowledgeBaseId,
                    documentId,
                    idempotencyHash,
                    fingerprint,
                    normalizedSource
            ));
        } catch (DuplicateKeyException exception) {
            // 写事务已经回滚，后续读取不会看见本请求留下的半成品事实。
            return resolveDuplicateWrite(
                    tenantId,
                    knowledgeBaseId,
                    null,
                    idempotencyHash,
                    fingerprint,
                    normalizedSource
            );
        }
    }

    private DocumentUploadAcceptedVO acceptNewDocumentTransaction(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            String documentName,
            String idempotencyHash,
            String fingerprint,
            StoredSourceObjectDTO source
    ) {
        requireActiveTenantAndKnowledgeBase(
                principal,
                tenantId,
                knowledgeBaseId
        );
        // 幂等检查必须早于名称和对象冲突判断，合法重放应返回原业务结果。
        DocumentUploadAcceptedVO replay = findReplay(
                tenantId,
                idempotencyHash,
                fingerprint
        );
        if (replay != null) {
            return replay;
        }
        requireUnusedSourceObject(source);
        if (documentMapper.countByTenantKnowledgeBaseAndName(
                tenantId,
                knowledgeBaseId,
                documentName
        ) != 0) {
            throw documentNameConflict();
        }

        LocalDateTime now = nowUtc();
        // 首次受理时尚无可检索版本，因此 activeVersionId 保持为空。
        DocumentEntity document = new DocumentEntity(
                null,
                tenantId,
                knowledgeBaseId,
                documentName,
                documentName,
                DOCUMENT_ACTIVE,
                null,
                null,
                null,
                null,
                principal.id(),
                now,
                now
        );
        if (documentMapper.insert(document) != 1 || document.getId() == null) {
            throw new IllegalStateException("Document was not created");
        }

        DocumentVersionEntity version = createVersion(
                tenantId,
                document.getId(),
                1,
                principal.id(),
                idempotencyHash,
                fingerprint,
                source,
                now
        );
        ProcessingJobEntity job = createInitialJob(tenantId, version.getId(), now);
        createOutbox(
                tenantId,
                knowledgeBaseId,
                document.getId(),
                version.getId(),
                job.getId(),
                now
        );
        if (documentMapper.updateLatestVersion(
                tenantId,
                document.getId(),
                version.getId(),
                now
        ) != 1) {
            throw new IllegalStateException("Document latest version was not updated");
        }
        document.setLatestVersionId(version.getId());
        return toVO(document, version, job);
    }

    private DocumentUploadAcceptedVO acceptNewVersionTransaction(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyHash,
            String fingerprint,
            StoredSourceObjectDTO source
    ) {
        requireActiveTenantAndKnowledgeBase(
                principal,
                tenantId,
                knowledgeBaseId
        );
        DocumentUploadAcceptedVO replay = findReplay(
                tenantId,
                idempotencyHash,
                fingerprint
        );
        if (replay != null) {
            return replay;
        }

        // Document 行锁串行化版本号分配和“最多一个 PROCESSING 候选”的检查。
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndIdForUpdate(
                tenantId,
                knowledgeBaseId,
                documentId
        );
        if (document == null || !DOCUMENT_ACTIVE.equals(document.getStatus())) {
            throw documentNotFound();
        }

        // 等待行锁期间并发事务可能已提交，获得锁后必须再次检查幂等结果。
        replay = findReplay(tenantId, idempotencyHash, fingerprint);
        if (replay != null) {
            return replay;
        }
        requireUnusedSourceObject(source);

        DocumentVersionEntity latest = loadVersion(
                tenantId,
                documentId,
                document.getLatestVersionId()
        );
        if (VERSION_PROCESSING.equals(latest.getStatus())) {
            throw conflict(
                    "DOCUMENT_VERSION_IN_PROGRESS",
                    "Document already has a version in progress"
            );
        }
        if (!VERSION_READY.equals(latest.getStatus())
                && !VERSION_FAILED.equals(latest.getStatus())) {
            throw new IllegalStateException("Document latest version status is invalid");
        }
        boolean sameAsLatest = source.getSourceSha256().equals(latest.getSourceSha256());
        if (VERSION_FAILED.equals(latest.getStatus()) && sameAsLatest) {
            ProcessingJobEntity latestJob = processingJobMapper
                    .findLatestByTenantAndVersionForUpdate(tenantId, latest.getId());
            // 可重试失败必须沿用原版本及其尝试历史；不可重试失败则允许在系统修复后
            // 以相同源文件创建下一版本，避免“不能重试也不能重新上传”的生命周期死锁。
            if (latestJob == null || !Boolean.FALSE.equals(latestJob.getFailureRetryable())) {
                throw conflict(
                        "FAILED_VERSION_RETRY_REQUIRED",
                        "The failed version should be retried instead of uploaded again"
                );
            }
        }
        if (!VERSION_FAILED.equals(latest.getStatus()) && sameAsLatest) {
            throw contentUnchanged();
        }
        // latest 可能是 FAILED；仍需与 active 比较，避免重新上传当前可检索内容。
        if (document.getActiveVersionId() != null) {
            DocumentVersionEntity active = loadVersion(
                    tenantId,
                    documentId,
                    document.getActiveVersionId()
            );
            if (!VERSION_READY.equals(active.getStatus())) {
                throw new IllegalStateException("Document active version is not ready");
            }
            if (source.getSourceSha256().equals(active.getSourceSha256())) {
                throw contentUnchanged();
            }
        }

        int versionNo;
        try {
            versionNo = Math.addExact(latest.getVersionNo(), 1);
        } catch (ArithmeticException exception) {
            throw conflict("DOCUMENT_VERSION_LIMIT_REACHED", "Version number limit reached");
        }
        LocalDateTime now = nowUtc();
        DocumentVersionEntity version = createVersion(
                tenantId,
                documentId,
                versionNo,
                principal.id(),
                idempotencyHash,
                fingerprint,
                source,
                now
        );
        ProcessingJobEntity job = createInitialJob(tenantId, version.getId(), now);
        // 任务和 Outbox 必须与版本同事务落库，避免提交后没有可调度工作。
        createOutbox(
                tenantId,
                knowledgeBaseId,
                documentId,
                version.getId(),
                job.getId(),
                now
        );
        if (documentMapper.updateLatestVersion(
                tenantId,
                documentId,
                version.getId(),
                now
        ) != 1) {
            throw new IllegalStateException("Document latest version was not updated");
        }
        document.setLatestVersionId(version.getId());
        document.setUpdatedAt(now);
        return toVO(document, version, job);
    }

    private DocumentVersionEntity createVersion(
            long tenantId,
            long documentId,
            int versionNo,
            long acceptedBy,
            String idempotencyHash,
            String fingerprint,
            StoredSourceObjectDTO source,
            LocalDateTime now
    ) {
        DocumentVersionEntity version = new DocumentVersionEntity(
                null,
                tenantId,
                documentId,
                versionNo,
                VERSION_PROCESSING,
                source.getSourceFormat(),
                source.getOriginalFilename(),
                source.getSourceBucket(),
                source.getSourceObjectKey(),
                source.getSourceSizeBytes(),
                source.getSourceSha256(),
                source.getSourceContentType(),
                idempotencyHash,
                fingerprint,
                acceptedBy,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
        if (documentVersionMapper.insert(version) != 1 || version.getId() == null) {
            throw new IllegalStateException("Document version was not created");
        }
        return version;
    }

    private ProcessingJobEntity createInitialJob(
            long tenantId,
            long versionId,
            LocalDateTime now
    ) {
        ProcessingJobEntity job = new ProcessingJobEntity(
                null,
                tenantId,
                versionId,
                JOB_TYPE_INGEST,
                1,
                JOB_PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
        if (processingJobMapper.insert(job) != 1 || job.getId() == null) {
            throw new IllegalStateException("Processing job was not created");
        }
        return job;
    }

    private void createOutbox(
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            long versionId,
            long jobId,
            LocalDateTime now
    ) {
        // Payload 仅使用稳定 ID；对象位置、内容摘要和凭证均不得进入消息。
        String payload = """
                {"tenantId":%d,"knowledgeBaseId":%d,"documentId":%d,"documentVersionId":%d,"processingJobId":%d}
                """.formatted(
                tenantId,
                knowledgeBaseId,
                documentId,
                versionId,
                jobId
        ).strip();
        OutboxEventEntity event = new OutboxEventEntity(
                null,
                tenantId,
                documentId,
                versionId,
                jobId,
                null,
                PROCESS_REQUESTED,
                payload,
                OUTBOX_PENDING,
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
        if (outboxEventMapper.insert(event) != 1 || event.getId() == null) {
            throw new IllegalStateException("Outbox event was not created");
        }
    }

    private DocumentUploadAcceptedVO findReplay(
            long tenantId,
            String idempotencyHash,
            String fingerprint
    ) {
        DocumentVersionEntity existing = documentVersionMapper
                .findByTenantAndIdempotencyHash(tenantId, idempotencyHash);
        if (existing == null) {
            return null;
        }
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            // 相同 Key 绑定了不同业务请求，必须拒绝而不能返回错误的旧结果。
            throw conflict(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency key was already used for a different upload"
            );
        }
        DocumentEntity document = documentMapper.findByTenantAndId(
                tenantId,
                existing.getDocumentId()
        );
        ProcessingJobEntity job = processingJobMapper.findInitialByTenantAndVersion(
                tenantId,
                existing.getId()
        );
        if (document == null || job == null) {
            throw new IllegalStateException("Idempotent upload facts are incomplete");
        }
        return toVO(document, existing, job);
    }

    private DocumentUploadAcceptedVO resolveDuplicateWrite(
            long tenantId,
            long knowledgeBaseId,
            String documentName,
            String idempotencyHash,
            String fingerprint,
            StoredSourceObjectDTO source
    ) {
        // 该方法在失败写事务之外运行，只读取数据库已经提交的并发结果。
        return executeRead(() -> {
            DocumentUploadAcceptedVO replay = findReplay(
                    tenantId,
                    idempotencyHash,
                    fingerprint
            );
            if (replay != null) {
                return replay;
            }
            if (documentVersionMapper.countBySourceObject(
                    source.getSourceBucket(),
                    source.getSourceObjectKey()
            ) != 0) {
                throw sourceObjectConflict();
            }
            if (documentName != null
                    && documentMapper.countByTenantKnowledgeBaseAndName(
                    tenantId,
                    knowledgeBaseId,
                    documentName
            ) != 0) {
                throw documentNameConflict();
            }
            throw conflict(
                    "DOCUMENT_UPLOAD_CONFLICT",
                    "Document upload conflicted with another request"
            );
        });
    }

    private void requireActiveTenantAndKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId
    ) {
        // 平台管理员不能代替租户管理员执行文档受理，避免越过租户责任边界。
        if (principal == null || !TENANT_ADMIN.equals(principal.role())) {
            throw forbidden(
                    "TENANT_RESOURCE_MANAGEMENT_FORBIDDEN",
                    "Tenant administrator is required"
            );
        }
        if (principal.id() == null || principal.id() < 1) {
            throw forbidden(
                    "TENANT_RESOURCE_MANAGEMENT_FORBIDDEN",
                    "Tenant administrator identity is invalid"
            );
        }
        if (principal.tenantId() == null || principal.tenantId().longValue() != tenantId) {
            throw forbidden("TENANT_SCOPE_FORBIDDEN", "Tenant is outside administrator scope");
        }
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null || !TENANT_ACTIVE.equals(tenant.getStatus())) {
            throw forbidden("TENANT_NOT_ACTIVE", "Tenant is not active");
        }
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, knowledgeBaseId)
        );
        // 不存在、跨租户和停用统一隐藏为资源不存在。
        if (knowledgeBase == null || !TENANT_ACTIVE.equals(knowledgeBase.getStatus())) {
            throw knowledgeBaseNotFound();
        }
    }

    private void requireUnusedSourceObject(StoredSourceObjectDTO source) {
        if (documentVersionMapper.countBySourceObject(
                source.getSourceBucket(),
                source.getSourceObjectKey()
        ) != 0) {
            throw sourceObjectConflict();
        }
    }

    /** 按 Tenant、Document 和版本指针三重条件加载，防止错误指针跨边界。 */
    private DocumentVersionEntity loadVersion(
            long tenantId,
            long documentId,
            Long versionId
    ) {
        if (versionId == null) {
            throw new IllegalStateException("Document version pointer is missing");
        }
        DocumentVersionEntity version = documentVersionMapper.findByTenantDocumentAndId(
                tenantId,
                documentId,
                versionId
        );
        if (version == null) {
            throw new IllegalStateException("Document version pointer is invalid");
        }
        return version;
    }

    /**
     * 规范化并验证可信对象描述；这里只校验描述本身，不声称对象存储中确实存在。
     */
    private StoredSourceObjectDTO normalizeSource(StoredSourceObjectDTO requested) {
        if (requested == null) {
            throw validation("Stored source object is required");
        }
        StoredSourceObjectDTO source = new StoredSourceObjectDTO();
        source.setOriginalFilename(normalizeOriginalFilename(
                requested.getOriginalFilename()
        ));
        source.setSourceFormat(validateSourceFormat(requested.getSourceFormat()));
        source.setSourceBucket(normalizeBucket(requested.getSourceBucket()));
        source.setSourceObjectKey(normalizeObjectKey(requested.getSourceObjectKey()));
        if (requested.getSourceSizeBytes() < 1) {
            throw validation("Source size must be positive");
        }
        source.setSourceSizeBytes(requested.getSourceSizeBytes());
        source.setSourceSha256(normalizeSha256(requested.getSourceSha256()));
        source.setSourceContentType(normalizeContentType(
                requested.getSourceContentType()
        ));
        validateFilenameFormat(source.getOriginalFilename(), source.getSourceFormat());
        return source;
    }

    private String normalizeDocumentName(String requestedName) {
        if (requestedName == null) {
            throw validation("Document name is required");
        }
        String name = requestedName.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 200) {
            throw validation("Document name must contain between 1 and 200 characters");
        }
        return name;
    }

    private String validateIdempotencyKey(String requestedKey) {
        if (requestedKey == null
                || requestedKey.isEmpty()
                || requestedKey.length() > 128
                || requestedKey.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw validation(
                    "Idempotency key must contain 1 to 128 visible ASCII characters"
            );
        }
        return requestedKey;
    }

    private String normalizeOriginalFilename(String requestedFilename) {
        if (requestedFilename == null) {
            throw validation("Original filename is required");
        }
        String filename = requestedFilename.strip();
        int length = filename.codePointCount(0, filename.length());
        if (length < 1
                || length > 512
                || filename.equals(".")
                || filename.equals("..")
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw validation("Original filename is invalid");
        }
        return filename;
    }

    private String validateSourceFormat(String requestedFormat) {
        try {
            return DocumentSourceFormat.fromCode(requestedFormat).getCode();
        } catch (IllegalArgumentException exception) {
            throw validation("Source format must be 1 (PDF), 2 (DOCX), 3 (TXT) or 4 (MARKDOWN)");
        }
    }

    private String normalizeBucket(String requestedBucket) {
        if (requestedBucket == null) {
            throw validation("Source bucket is required");
        }
        String bucket = requestedBucket.strip();
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw validation("Source bucket is invalid");
        }
        return bucket;
    }

    private String normalizeObjectKey(String requestedObjectKey) {
        if (requestedObjectKey == null
                || requestedObjectKey.isEmpty()
                || requestedObjectKey.length() > 1024
                || requestedObjectKey.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw validation(
                    "Source object key must contain 1 to 1024 visible ASCII characters"
            );
        }
        return requestedObjectKey;
    }

    private String normalizeSha256(String requestedSha256) {
        if (requestedSha256 == null || !requestedSha256.matches("[0-9a-f]{64}")) {
            throw validation("Source SHA-256 must be 64 lowercase hexadecimal characters");
        }
        return requestedSha256;
    }

    private String normalizeContentType(String requestedContentType) {
        if (requestedContentType == null) {
            return null;
        }
        String contentType = requestedContentType.strip();
        if (contentType.isEmpty()) {
            return null;
        }
        if (contentType.length() > 255
                || contentType.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw validation("Source Content-Type is invalid");
        }
        return contentType;
    }

    private void validateFilenameFormat(String filename, String format) {
        String lowercase = filename.toLowerCase(Locale.ROOT);
        boolean matches = switch (DocumentSourceFormat.fromCode(format)) {
            case PDF -> lowercase.endsWith(".pdf");
            case DOCX -> lowercase.endsWith(".docx");
            case TXT -> lowercase.endsWith(".txt");
            case MARKDOWN -> lowercase.endsWith(".md") || lowercase.endsWith(".markdown");
        };
        if (!matches) {
            throw validation("Original filename does not match source format");
        }
    }

    /**
     * 以“长度 + UTF-8 字节”编码每个字段，避免简单拼接产生边界歧义。
     */
    private String fingerprint(String... values) {
        MessageDigest digest = newSha256();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(
                newSha256().digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private DocumentUploadAcceptedVO toVO(
            DocumentEntity document,
            DocumentVersionEntity version,
            ProcessingJobEntity job
    ) {
        return new DocumentUploadAcceptedVO(
                document.getId(),
                version.getId(),
                version.getVersionNo(),
                job.getId(),
                document.getStatus(),
                version.getStatus(),
                job.getStatus(),
                version.getCreatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    /** 在 READ COMMITTED 写事务中执行完整受理操作。 */
    private DocumentUploadAcceptedVO executeWrite(
            Supplier<DocumentUploadAcceptedVO> operation
    ) {
        return Objects.requireNonNull(writeTransaction.execute(status -> operation.get()));
    }

    /** 在新的 READ COMMITTED 只读事务中解析并发提交结果。 */
    private DocumentUploadAcceptedVO executeRead(
            Supplier<DocumentUploadAcceptedVO> operation
    ) {
        return Objects.requireNonNull(readTransaction.execute(status -> operation.get()));
    }

    private void requirePositiveId(long id, String message) {
        if (id < 1) {
            throw validation(message);
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException forbidden(String code, String message) {
        return new BusinessException(FORBIDDEN, code, message);
    }

    private BusinessException knowledgeBaseNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "KNOWLEDGE_BASE_NOT_FOUND",
                "KnowledgeBase was not found"
        );
    }

    private BusinessException documentNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "DOCUMENT_NOT_FOUND",
                "Document was not found"
        );
    }

    private BusinessException documentNameConflict() {
        return conflict(
                "DOCUMENT_NAME_CONFLICT",
                "Document name already exists in this knowledge base"
        );
    }

    private BusinessException contentUnchanged() {
        return conflict(
                "DOCUMENT_CONTENT_UNCHANGED",
                "Uploaded content is unchanged"
        );
    }

    private BusinessException sourceObjectConflict() {
        return conflict(
                "SOURCE_OBJECT_CONFLICT",
                "Stored source object is already referenced by another version"
        );
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(CONFLICT, code, message);
    }
}
