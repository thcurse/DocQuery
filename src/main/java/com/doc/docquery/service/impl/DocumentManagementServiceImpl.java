package com.doc.docquery.service.impl;

import com.doc.docquery.dto.DocumentManagementPageQueryDTO;
import com.doc.docquery.dto.ProcessingJobManagementPageQueryDTO;
import com.doc.docquery.dto.ProcessingJobManagementRowDTO;
import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.entity.DocumentDeletionJobEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import com.doc.docquery.entity.OutboxEventEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.enums.OutboxEventStatus;
import com.doc.docquery.enums.OutboxEventType;
import com.doc.docquery.enums.ProcessingJobStatus;
import com.doc.docquery.enums.ProcessingJobType;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentDeletionJobMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.mapper.KnowledgeBaseMapper;
import com.doc.docquery.mapper.OutboxEventMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentManagementService;
import com.doc.docquery.vo.DocumentDeletionJobVO;
import com.doc.docquery.vo.DocumentManagementVO;
import com.doc.docquery.vo.DocumentVersionSummaryVO;
import com.doc.docquery.vo.DocumentVersionVO;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.ProcessingJobAttemptVO;
import com.doc.docquery.vo.ProcessingJobDetailVO;
import com.doc.docquery.vo.ProcessingJobRetryAcceptedVO;
import com.doc.docquery.vo.ProcessingJobVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/** N4.1 管理查询与人工重试实现；MySQL 始终是状态和幂等事实源。 */
@Service
public class DocumentManagementServiceImpl implements DocumentManagementService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String DOCUMENT_ACTIVE = DocumentStatus.ACTIVE.getCode();
    private static final String VERSION_PROCESSING = DocumentVersionStatus.PROCESSING.getCode();
    private static final String VERSION_FAILED = DocumentVersionStatus.FAILED.getCode();
    private static final String JOB_PENDING = ProcessingJobStatus.PENDING.getCode();
    private static final String JOB_RUNNING = ProcessingJobStatus.RUNNING.getCode();
    private static final String JOB_FAILED = ProcessingJobStatus.FAILED.getCode();
    private static final String JOB_INGEST = ProcessingJobType.INGEST.getCode();
    private static final String OUTBOX_PENDING = OutboxEventStatus.PENDING.getCode();
    private static final String PROCESS_REQUESTED =
            OutboxEventType.DOCUMENT_VERSION_PROCESS_REQUESTED.getCode();

    private final TenantMapper tenantMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final ProcessingJobMapper jobMapper;
    private final DocumentDeletionJobMapper deletionJobMapper;
    private final OutboxEventMapper outboxMapper;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public DocumentManagementServiceImpl(
            TenantMapper tenantMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            DocumentMapper documentMapper,
            DocumentVersionMapper versionMapper,
            ProcessingJobMapper jobMapper,
            DocumentDeletionJobMapper deletionJobMapper,
            OutboxEventMapper outboxMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.tenantMapper = tenantMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.jobMapper = jobMapper;
        this.deletionJobMapper = deletionJobMapper;
        this.outboxMapper = outboxMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<DocumentManagementVO> listDocuments(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            DocumentManagementPageQueryDTO query
    ) {
        requireTenantScope(principal, tenantId);
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        validateDocumentQuery(query);
        query.setTenantId(tenantId);
        query.setKnowledgeBaseId(knowledgeBaseId);
        List<DocumentEntity> documents = documentMapper.findManagementPage(query);
        Map<Long, DocumentVersionEntity> versions = loadReferencedVersions(
                tenantId,
                documents
        );
        Map<Long, ProcessingJobEntity> latestJobs = loadLatestJobs(
                tenantId,
                documents.stream().map(DocumentEntity::getLatestVersionId).toList()
        );
        Map<Long, DocumentDeletionJobEntity> deletionJobs = loadLatestDeletionJobs(
                tenantId,
                documents.stream().map(DocumentEntity::getId).toList()
        );
        List<DocumentManagementVO> items = documents.stream()
                .map(document -> toDocumentVO(
                        document, versions, latestJobs, deletionJobs.get(document.getId())
                ))
                .toList();
        return new PageVO<>(
                items,
                Math.toIntExact(query.getOffset() / query.getLimit()),
                query.getLimit(),
                documentMapper.countManagementPage(query)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentManagementVO getDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId
    ) {
        requireTenantScope(principal, tenantId);
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        requirePositive(documentId, "Document ID");
        DocumentEntity document = requireDocument(tenantId, knowledgeBaseId, documentId);
        Map<Long, DocumentVersionEntity> versions = loadReferencedVersions(
                tenantId,
                List.of(document)
        );
        Map<Long, ProcessingJobEntity> jobs = loadLatestJobs(
                tenantId,
                List.of(document.getLatestVersionId())
        );
        DocumentDeletionJobEntity deletionJob = deletionJobMapper
                .findLatestByTenantAndDocument(tenantId, documentId);
        return toDocumentVO(document, versions, jobs, deletionJob);
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<DocumentVersionVO> listVersions(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            int page,
            int size
    ) {
        requireTenantScope(principal, tenantId);
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        requirePositive(documentId, "Document ID");
        validatePage(page, size);
        DocumentEntity document = requireDocument(tenantId, knowledgeBaseId, documentId);
        long offset = (long) page * size;
        List<DocumentVersionEntity> versions = versionMapper.findManagementPage(
                tenantId,
                documentId,
                offset,
                size
        );
        Map<Long, ProcessingJobEntity> jobs = loadLatestJobs(
                tenantId,
                versions.stream().map(DocumentVersionEntity::getId).toList()
        );
        List<DocumentVersionVO> items = versions.stream()
                .map(version -> toVersionVO(
                        version,
                        version.getId().equals(document.getActiveVersionId()),
                        jobs.get(version.getId())
                ))
                .toList();
        return new PageVO<>(
                items,
                page,
                size,
                versionMapper.countByTenantAndDocument(tenantId, documentId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<ProcessingJobVO> listProcessingJobs(
            AdminPrincipal principal,
            long tenantId,
            ProcessingJobManagementPageQueryDTO query
    ) {
        requireTenantScope(principal, tenantId);
        validateJobQuery(query);
        query.setTenantId(tenantId);
        List<ProcessingJobVO> items = jobMapper.findManagementPage(query).stream()
                .map(this::toJobVO)
                .toList();
        return new PageVO<>(
                items,
                Math.toIntExact(query.getOffset() / query.getLimit()),
                query.getLimit(),
                jobMapper.countManagementPage(query)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessingJobDetailVO getProcessingJob(
            AdminPrincipal principal,
            long tenantId,
            long processingJobId
    ) {
        requireTenantScope(principal, tenantId);
        requirePositive(processingJobId, "ProcessingJob ID");
        ProcessingJobManagementRowDTO row = jobMapper.findManagementByTenantAndId(
                tenantId,
                processingJobId
        );
        if (row == null) {
            throw jobNotFound();
        }
        List<ProcessingJobAttemptVO> attempts = jobMapper
                .findAttemptsByTenantAndVersion(tenantId, row.getDocumentVersionId())
                .stream()
                .map(this::toAttemptVO)
                .toList();
        return new ProcessingJobDetailVO(toJobVO(row), attempts);
    }

    @Override
    public ProcessingJobRetryAcceptedVO retryProcessingJob(
            AdminPrincipal principal,
            long tenantId,
            long processingJobId,
            String idempotencyKey
    ) {
        requireTenantScope(principal, tenantId);
        requirePositive(processingJobId, "ProcessingJob ID");
        String normalizedKey = validateIdempotencyKey(idempotencyKey);
        String keyHash = sha256(normalizedKey);
        String fingerprint = sha256(
                "N4.1_PROCESSING_JOB_RETRY\n" + tenantId + "\n" + processingJobId
        );

        ProcessingJobRetryAcceptedVO replay = readTransaction.execute(
                status -> findReplay(tenantId, processingJobId, keyHash, fingerprint)
        );
        if (replay != null) {
            return replay;
        }
        try {
            ProcessingJobRetryAcceptedVO accepted = writeTransaction.execute(
                    status -> retryLocked(
                            tenantId,
                            processingJobId,
                            keyHash,
                            fingerprint
                    )
            );
            if (accepted == null) {
                throw new IllegalStateException("Processing retry transaction returned no result");
            }
            return accepted;
        } catch (DuplicateKeyException exception) {
            ProcessingJobRetryAcceptedVO winner = readTransaction.execute(
                    status -> findReplay(tenantId, processingJobId, keyHash, fingerprint)
            );
            if (winner != null) {
                return winner;
            }
            throw new BusinessException(
                    CONFLICT,
                    "RETRY_ALREADY_IN_PROGRESS",
                    "A retry is already in progress"
            );
        }
    }

    private ProcessingJobRetryAcceptedVO retryLocked(
            long tenantId,
            long processingJobId,
            String keyHash,
            String fingerprint
    ) {
        ProcessingJobRetryAcceptedVO replay = findReplay(
                tenantId,
                processingJobId,
                keyHash,
                fingerprint
        );
        if (replay != null) {
            return replay;
        }

        ProcessingJobEntity requested = jobMapper.findByTenantAndId(
                tenantId,
                processingJobId
        );
        if (requested == null) {
            throw jobNotFound();
        }
        DocumentVersionEntity untrustedVersion = versionMapper.findByTenantAndId(
                tenantId,
                requested.getDocumentVersionId()
        );
        if (untrustedVersion == null) {
            throw jobNotFound();
        }
        DocumentEntity untrustedDocument = documentMapper.findByTenantAndId(
                tenantId,
                untrustedVersion.getDocumentId()
        );
        if (untrustedDocument == null) {
            throw jobNotFound();
        }

        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.findByTenantAndIdForUpdate(
                new TenantResourceQueryDTO(tenantId, untrustedDocument.getKnowledgeBaseId())
        );
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndIdForUpdate(
                tenantId,
                untrustedDocument.getKnowledgeBaseId(),
                untrustedDocument.getId()
        );
        DocumentVersionEntity version = versionMapper.findByTenantDocumentAndIdForUpdate(
                tenantId,
                untrustedDocument.getId(),
                untrustedVersion.getId()
        );
        if (knowledgeBase == null || document == null || version == null) {
            throw jobNotFound();
        }
        if (!ACTIVE.equals(knowledgeBase.getStatus())) {
            throw conflict(
                    "KNOWLEDGE_BASE_NOT_ACTIVE",
                    "KnowledgeBase is not active"
            );
        }
        if (!DOCUMENT_ACTIVE.equals(document.getStatus())) {
            throw conflict("DOCUMENT_NOT_ACTIVE", "Document is not active");
        }
        if (!version.getId().equals(document.getLatestVersionId())) {
            throw conflict(
                    "DOCUMENT_VERSION_SUPERSEDED",
                    "Document version is no longer the latest version"
            );
        }
        if (jobMapper.countInProgressByTenantAndVersion(
                tenantId,
                version.getId(),
                JOB_PENDING,
                JOB_RUNNING
        ) != 0) {
            throw conflict(
                    "RETRY_ALREADY_IN_PROGRESS",
                    "A retry is already in progress"
            );
        }
        ProcessingJobEntity latest = jobMapper.findLatestByTenantAndVersionForUpdate(
                tenantId,
                version.getId()
        );
        if (latest == null || !latest.getId().equals(processingJobId)) {
            throw conflict(
                    "PROCESSING_JOB_SUPERSEDED",
                    "Processing job is not the latest attempt"
            );
        }
        if (!JOB_FAILED.equals(latest.getStatus()) || !VERSION_FAILED.equals(version.getStatus())) {
            throw conflict(
                    "PROCESSING_JOB_NOT_FAILED",
                    "Processing job is not in a retryable failed state"
            );
        }
        if (!Boolean.TRUE.equals(latest.getFailureRetryable())) {
            throw conflict(
                    "PROCESSING_JOB_NOT_RETRYABLE",
                    "Processing job failure is not retryable"
            );
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (versionMapper.resetFailedForRetry(
                version.getId(),
                tenantId,
                now,
                VERSION_FAILED,
                VERSION_PROCESSING
        ) != 1) {
            throw conflict(
                    "RETRY_ALREADY_IN_PROGRESS",
                    "A retry is already in progress"
            );
        }
        ProcessingJobEntity retry = new ProcessingJobEntity(
                null,
                tenantId,
                version.getId(),
                JOB_INGEST,
                latest.getAttemptNo() + 1,
                JOB_PENDING,
                null,
                null,
                null,
                null,
                null,
                keyHash,
                fingerprint,
                null,
                null,
                now,
                now
        );
        if (jobMapper.insert(retry) != 1 || retry.getId() == null) {
            throw new IllegalStateException("Processing retry was not created");
        }
        createOutbox(
                tenantId,
                knowledgeBase.getId(),
                document.getId(),
                version.getId(),
                retry.getId(),
                now
        );
        return new ProcessingJobRetryAcceptedVO(
                processingJobId,
                document.getId(),
                version.getId(),
                retry.getId(),
                retry.getAttemptNo(),
                VERSION_PROCESSING,
                JOB_PENDING,
                false,
                now.atOffset(ZoneOffset.UTC)
        );
    }

    private ProcessingJobRetryAcceptedVO findReplay(
            long tenantId,
            long sourceProcessingJobId,
            String keyHash,
            String fingerprint
    ) {
        ProcessingJobEntity existing = jobMapper.findByTenantAndIdempotencyHash(
                tenantId,
                keyHash
        );
        if (existing == null) {
            return null;
        }
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key is already bound to another retry command"
            );
        }
        DocumentVersionEntity version = versionMapper.findByTenantAndId(
                tenantId,
                existing.getDocumentVersionId()
        );
        if (version == null) {
            throw new IllegalStateException("Retry version fact is missing");
        }
        return new ProcessingJobRetryAcceptedVO(
                sourceProcessingJobId,
                version.getDocumentId(),
                version.getId(),
                existing.getId(),
                existing.getAttemptNo(),
                version.getStatus(),
                existing.getStatus(),
                true,
                utc(existing.getCreatedAt())
        );
    }

    private void createOutbox(
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            long versionId,
            long jobId,
            LocalDateTime now
    ) {
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
        if (outboxMapper.insert(event) != 1 || event.getId() == null) {
            throw new IllegalStateException("Retry Outbox event was not created");
        }
    }

    private Map<Long, DocumentVersionEntity> loadReferencedVersions(
            long tenantId,
            List<DocumentEntity> documents
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        for (DocumentEntity document : documents) {
            if (document.getActiveVersionId() != null) {
                ids.add(document.getActiveVersionId());
            }
            if (document.getLatestVersionId() != null) {
                ids.add(document.getLatestVersionId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DocumentVersionEntity> result = new HashMap<>();
        for (DocumentVersionEntity version : versionMapper.findByTenantAndIds(
                tenantId,
                new ArrayList<>(ids)
        )) {
            result.put(version.getId(), version);
        }
        return result;
    }

    private Map<Long, ProcessingJobEntity> loadLatestJobs(
            long tenantId,
            List<Long> requestedVersionIds
    ) {
        List<Long> ids = requestedVersionIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProcessingJobEntity> result = new HashMap<>();
        for (ProcessingJobEntity job : jobMapper.findLatestByTenantAndVersions(
                tenantId,
                ids
        )) {
            result.put(job.getDocumentVersionId(), job);
        }
        return result;
    }

    private Map<Long, DocumentDeletionJobEntity> loadLatestDeletionJobs(
            long tenantId,
            List<Long> requestedDocumentIds
    ) {
        List<Long> ids = requestedDocumentIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DocumentDeletionJobEntity> result = new HashMap<>();
        for (DocumentDeletionJobEntity job : deletionJobMapper
                .findLatestByTenantAndDocuments(tenantId, ids)) {
            result.put(job.getDocumentId(), job);
        }
        return result;
    }

    private DocumentManagementVO toDocumentVO(
            DocumentEntity document,
            Map<Long, DocumentVersionEntity> versions,
            Map<Long, ProcessingJobEntity> latestJobs,
            DocumentDeletionJobEntity latestDeletionJob
    ) {
        DocumentVersionEntity active = document.getActiveVersionId() == null
                ? null : versions.get(document.getActiveVersionId());
        DocumentVersionEntity latest = document.getLatestVersionId() == null
                ? null : versions.get(document.getLatestVersionId());
        if (latest == null) {
            throw new IllegalStateException("Document latest version fact is missing");
        }
        return new DocumentManagementVO(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getName(),
                document.getStatus(),
                active == null ? null : toSummary(active, true, latestJobs.get(active.getId())),
                toSummary(
                        latest,
                        latest.getId().equals(document.getActiveVersionId()),
                        latestJobs.get(latest.getId())
                ),
                utc(document.getDeletionRequestedAt()),
                utc(document.getDeletedAt()),
                latestDeletionJob == null ? null : toDeletionJobVO(latestDeletionJob),
                utc(document.getCreatedAt()),
                utc(document.getUpdatedAt())
        );
    }

    private DocumentVersionSummaryVO toSummary(
            DocumentVersionEntity version,
            boolean active,
            ProcessingJobEntity latestJob
    ) {
        return new DocumentVersionSummaryVO(
                version.getId(),
                version.getVersionNo(),
                version.getStatus(),
                active,
                version.getFailureCode(),
                version.getFailureMessage(),
                latestJob == null ? null : latestJob.getFailureRetryable(),
                utc(version.getReadyAt()),
                utc(version.getFailedAt())
        );
    }

    private DocumentVersionVO toVersionVO(
            DocumentVersionEntity version,
            boolean active,
            ProcessingJobEntity latestJob
    ) {
        return new DocumentVersionVO(
                version.getId(),
                version.getVersionNo(),
                version.getStatus(),
                active,
                version.getSourceFormat(),
                version.getOriginalFilename(),
                version.getSourceSizeBytes(),
                version.getFailureCode(),
                version.getFailureMessage(),
                latestJob == null ? null : latestJob.getFailureRetryable(),
                utc(version.getReadyAt()),
                utc(version.getFailedAt()),
                utc(version.getContentDeletedAt()),
                utc(version.getCreatedAt()),
                utc(version.getUpdatedAt()),
                latestJob == null ? null : toAttemptVO(latestJob)
        );
    }

    private DocumentDeletionJobVO toDeletionJobVO(DocumentDeletionJobEntity job) {
        return new DocumentDeletionJobVO(
                job.getId(),
                job.getAttemptNo(),
                job.getStatus(),
                job.getFailureCode(),
                job.getFailureMessage(),
                job.getFailureRetryable(),
                job.getRequestedByAdminId(),
                utc(job.getStartedAt()),
                utc(job.getFinishedAt()),
                utc(job.getCreatedAt()),
                utc(job.getUpdatedAt())
        );
    }

    private ProcessingJobVO toJobVO(ProcessingJobManagementRowDTO row) {
        return new ProcessingJobVO(
                row.getId(),
                row.getKnowledgeBaseId(),
                row.getKnowledgeBaseName(),
                row.getDocumentId(),
                row.getDocumentName(),
                row.getDocumentVersionId(),
                row.getVersionNo(),
                row.getJobType(),
                row.getAttemptNo(),
                row.getStatus(),
                row.getFailureCode(),
                row.getFailureMessage(),
                row.getFailureRetryable(),
                utc(row.getStartedAt()),
                utc(row.getFinishedAt()),
                utc(row.getCreatedAt()),
                utc(row.getUpdatedAt())
        );
    }

    private ProcessingJobAttemptVO toAttemptVO(ProcessingJobEntity job) {
        return new ProcessingJobAttemptVO(
                job.getId(),
                job.getAttemptNo(),
                job.getStatus(),
                job.getFailureCode(),
                job.getFailureMessage(),
                job.getFailureRetryable(),
                utc(job.getStartedAt()),
                utc(job.getFinishedAt()),
                utc(job.getCreatedAt()),
                utc(job.getUpdatedAt())
        );
    }

    private void requireTenantScope(AdminPrincipal principal, long tenantId) {
        if (tenantId < 1) {
            throw validation("Tenant ID must be positive");
        }
        if (principal == null || !TENANT_ADMIN.equals(principal.role())) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_RESOURCE_MANAGEMENT_FORBIDDEN",
                    "Tenant administrator is required"
            );
        }
        if (principal.tenantId() == null || principal.tenantId().longValue() != tenantId) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_SCOPE_FORBIDDEN",
                    "Tenant is outside administrator scope"
            );
        }
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null || !ACTIVE.equals(tenant.getStatus())) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_NOT_ACTIVE",
                    "Tenant is not active"
            );
        }
    }

    private KnowledgeBaseEntity requireKnowledgeBase(long tenantId, long knowledgeBaseId) {
        requirePositive(knowledgeBaseId, "KnowledgeBase ID");
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.findByTenantAndId(
                new TenantResourceQueryDTO(tenantId, knowledgeBaseId)
        );
        if (knowledgeBase == null) {
            throw new BusinessException(
                    NOT_FOUND,
                    "KNOWLEDGE_BASE_NOT_FOUND",
                    "KnowledgeBase was not found"
            );
        }
        return knowledgeBase;
    }

    private DocumentEntity requireDocument(
            long tenantId,
            long knowledgeBaseId,
            long documentId
    ) {
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndId(
                tenantId,
                knowledgeBaseId,
                documentId
        );
        if (document == null) {
            throw new BusinessException(
                    NOT_FOUND,
                    "DOCUMENT_NOT_FOUND",
                    "Document was not found"
            );
        }
        return document;
    }

    private void validateDocumentQuery(DocumentManagementPageQueryDTO query) {
        if (query == null || query.getOffset() < 0 || query.getLimit() < 1
                || query.getLimit() > 100) {
            throw validation("Document pagination is invalid");
        }
        if (query.getNamePattern() != null) {
            String name = query.getNamePattern().strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 200) {
                throw validation("Document name filter is invalid");
            }
            query.setNamePattern(escapeLike(name));
        }
        if (query.getDocumentStatus() != null
                && !isDocumentStatus(query.getDocumentStatus())) {
            throw validation("Document status filter is invalid");
        }
        if (query.getLatestVersionStatus() != null
                && !isVersionStatus(query.getLatestVersionStatus())) {
            throw validation("Latest version status filter is invalid");
        }
    }

    private void validateJobQuery(ProcessingJobManagementPageQueryDTO query) {
        if (query == null || query.getOffset() < 0 || query.getLimit() < 1
                || query.getLimit() > 100) {
            throw validation("ProcessingJob pagination is invalid");
        }
        positiveNullable(query.getKnowledgeBaseId(), "KnowledgeBase ID");
        positiveNullable(query.getDocumentId(), "Document ID");
        positiveNullable(query.getDocumentVersionId(), "DocumentVersion ID");
        if (query.getStatus() != null && !isJobStatus(query.getStatus())) {
            throw validation("ProcessingJob status filter is invalid");
        }
        if (query.getFrom() != null && query.getTo() != null
                && !query.getFrom().isBefore(query.getTo())) {
            throw validation("ProcessingJob time range is invalid");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw validation("Version pagination is invalid");
        }
    }

    private String validateIdempotencyKey(String requested) {
        if (requested == null || requested.isEmpty() || requested.length() > 128
                || requested.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
            throw validation(
                    "Idempotency-Key must contain 1 to 128 visible ASCII characters"
            );
        }
        return requested;
    }

    private boolean isDocumentStatus(String status) {
        for (DocumentStatus value : DocumentStatus.values()) {
            if (value.getCode().equals(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVersionStatus(String status) {
        for (DocumentVersionStatus value : DocumentVersionStatus.values()) {
            if (value.getCode().equals(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJobStatus(String status) {
        for (ProcessingJobStatus value : ProcessingJobStatus.values()) {
            if (value.getCode().equals(status)) {
                return true;
            }
        }
        return false;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void requirePositive(long value, String label) {
        if (value < 1) {
            throw validation(label + " must be positive");
        }
    }

    private void positiveNullable(Long value, String label) {
        if (value != null && value < 1) {
            throw validation(label + " must be positive");
        }
    }

    private OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException jobNotFound() {
        return new BusinessException(
                NOT_FOUND,
                "PROCESSING_JOB_NOT_FOUND",
                "Processing job was not found"
        );
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(CONFLICT, code, message);
    }
}
