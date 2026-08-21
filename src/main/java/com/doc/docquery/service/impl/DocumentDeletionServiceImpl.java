package com.doc.docquery.service.impl;

import com.doc.docquery.dto.TenantResourceQueryDTO;
import com.doc.docquery.entity.DocumentDeletionJobEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.KnowledgeBaseEntity;
import com.doc.docquery.entity.OutboxEventEntity;
import com.doc.docquery.entity.TenantEntity;
import com.doc.docquery.enums.AdminRole;
import com.doc.docquery.enums.DocumentDeletionJobStatus;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.enums.OutboxEventStatus;
import com.doc.docquery.enums.OutboxEventType;
import com.doc.docquery.enums.ProcessingJobStatus;
import com.doc.docquery.enums.StatusCode;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.DocumentDeletionJobMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.KnowledgeBaseMapper;
import com.doc.docquery.mapper.OutboxEventMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.mapper.TenantMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentDeletionService;
import com.doc.docquery.vo.DocumentDeletionAcceptedVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static com.doc.docquery.exception.BusinessException.Failure.CONFLICT;
import static com.doc.docquery.exception.BusinessException.Failure.FORBIDDEN;
import static com.doc.docquery.exception.BusinessException.Failure.NOT_FOUND;
import static com.doc.docquery.exception.BusinessException.Failure.VALIDATION;

/** N4.2 删除受理与人工重试；MySQL 事务是不可见性和幂等性的事实边界。 */
@Service
public class DocumentDeletionServiceImpl implements DocumentDeletionService {

    private static final String TENANT_ADMIN = AdminRole.TENANT_ADMIN.getCode();
    private static final String TENANT_ACTIVE = StatusCode.ACTIVE.getCode();
    private static final String DOCUMENT_ACTIVE = DocumentStatus.ACTIVE.getCode();
    private static final String DOCUMENT_DELETING = DocumentStatus.DELETING.getCode();
    private static final String DOCUMENT_DELETED = DocumentStatus.DELETED.getCode();
    private static final String JOB_PENDING = DocumentDeletionJobStatus.PENDING.getCode();
    private static final String JOB_RUNNING = DocumentDeletionJobStatus.RUNNING.getCode();
    private static final String JOB_FAILED = DocumentDeletionJobStatus.FAILED.getCode();
    private static final String PROCESS_PENDING = ProcessingJobStatus.PENDING.getCode();
    private static final String PROCESS_RUNNING = ProcessingJobStatus.RUNNING.getCode();
    private static final String OUTBOX_PENDING = OutboxEventStatus.PENDING.getCode();
    private static final String DELETE_REQUESTED = OutboxEventType.DOCUMENT_DELETE_REQUESTED.getCode();

    private final TenantMapper tenantMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final ProcessingJobMapper processingJobMapper;
    private final DocumentDeletionJobMapper deletionJobMapper;
    private final OutboxEventMapper outboxMapper;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public DocumentDeletionServiceImpl(
            TenantMapper tenantMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            DocumentMapper documentMapper,
            ProcessingJobMapper processingJobMapper,
            DocumentDeletionJobMapper deletionJobMapper,
            OutboxEventMapper outboxMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.tenantMapper = tenantMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.processingJobMapper = processingJobMapper;
        this.deletionJobMapper = deletionJobMapper;
        this.outboxMapper = outboxMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public DocumentDeletionAcceptedVO deleteDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    ) {
        requireTenantScope(principal, tenantId);
        validateResourceIds(knowledgeBaseId, documentId);
        String keyHash = sha256(validateIdempotencyKey(idempotencyKey));
        String fingerprint = sha256(
                "N4.2_DOCUMENT_DELETE\n" + tenantId + "\n"
                        + knowledgeBaseId + "\n" + documentId
        );
        DocumentDeletionAcceptedVO replay = readTransaction.execute(
                status -> findReplay(tenantId, knowledgeBaseId, documentId, keyHash, fingerprint)
        );
        if (replay != null) {
            return replay;
        }
        try {
            return writeTransaction.execute(status -> deleteLocked(
                    principal.id(), tenantId, knowledgeBaseId, documentId, keyHash, fingerprint
            ));
        } catch (DuplicateKeyException exception) {
            DocumentDeletionAcceptedVO winner = readTransaction.execute(
                    status -> findReplay(
                            tenantId, knowledgeBaseId, documentId, keyHash, fingerprint
                    )
            );
            if (winner != null) {
                return winner;
            }
            throw conflict(
                    "DOCUMENT_DELETION_IN_PROGRESS",
                    "Document deletion has already been accepted"
            );
        }
    }

    @Override
    public DocumentDeletionAcceptedVO retryDeletion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String idempotencyKey
    ) {
        requireTenantScope(principal, tenantId);
        validateResourceIds(knowledgeBaseId, documentId);
        String keyHash = sha256(validateIdempotencyKey(idempotencyKey));
        String fingerprint = sha256(
                "N4.2_DOCUMENT_DELETE_RETRY\n" + tenantId + "\n"
                        + knowledgeBaseId + "\n" + documentId
        );
        DocumentDeletionAcceptedVO replay = readTransaction.execute(
                status -> findReplay(tenantId, knowledgeBaseId, documentId, keyHash, fingerprint)
        );
        if (replay != null) {
            return replay;
        }
        try {
            DocumentDeletionAcceptedVO accepted = writeTransaction.execute(
                    status -> retryLocked(
                            principal.id(), tenantId, knowledgeBaseId, documentId,
                            keyHash, fingerprint
                    )
            );
            if (accepted == null) {
                throw new IllegalStateException("Deletion retry transaction returned no result");
            }
            return accepted;
        } catch (DuplicateKeyException exception) {
            DocumentDeletionAcceptedVO winner = readTransaction.execute(
                    status -> findReplay(
                            tenantId, knowledgeBaseId, documentId, keyHash, fingerprint
                    )
            );
            if (winner != null) {
                return winner;
            }
            throw conflict(
                    "DOCUMENT_DELETION_IN_PROGRESS",
                    "A document deletion attempt is already in progress"
            );
        }
    }

    private DocumentDeletionAcceptedVO deleteLocked(
            Long adminId,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String keyHash,
            String fingerprint
    ) {
        DocumentDeletionAcceptedVO replay = findReplay(
                tenantId, knowledgeBaseId, documentId, keyHash, fingerprint
        );
        if (replay != null) {
            return replay;
        }
        requireKnowledgeBaseLocked(tenantId, knowledgeBaseId);
        DocumentEntity document = requireDocumentLocked(tenantId, knowledgeBaseId, documentId);
        if (DOCUMENT_DELETED.equals(document.getStatus())) {
            return null;
        }
        if (!DOCUMENT_ACTIVE.equals(document.getStatus())) {
            throw conflict(
                    "DOCUMENT_DELETION_IN_PROGRESS",
                    "Document deletion has already been accepted"
            );
        }
        if (processingJobMapper.countInProgressByTenantAndDocument(
                tenantId, documentId, PROCESS_PENDING, PROCESS_RUNNING
        ) != 0) {
            throw conflict(
                    "DOCUMENT_PROCESSING_IN_PROGRESS",
                    "Document has an ingestion job in progress"
            );
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (documentMapper.beginDeletion(
                tenantId, knowledgeBaseId, documentId,
                DOCUMENT_ACTIVE, DOCUMENT_DELETING, now
        ) != 1) {
            throw conflict(
                    "DOCUMENT_DELETION_IN_PROGRESS",
                    "Document deletion has already been accepted"
            );
        }
        DocumentDeletionJobEntity job = new DocumentDeletionJobEntity(
                null, tenantId, knowledgeBaseId, documentId, 1, JOB_PENDING,
                null, null, null, null, null,
                keyHash, fingerprint, adminId,
                null, null, now, now
        );
        insertJobAndOutbox(job, now);
        return accepted(documentId, DOCUMENT_DELETING, job, false);
    }

    private DocumentDeletionAcceptedVO retryLocked(
            Long adminId,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String keyHash,
            String fingerprint
    ) {
        DocumentDeletionAcceptedVO replay = findReplay(
                tenantId, knowledgeBaseId, documentId, keyHash, fingerprint
        );
        if (replay != null) {
            return replay;
        }
        requireKnowledgeBaseLocked(tenantId, knowledgeBaseId);
        DocumentEntity document = requireDocumentLocked(tenantId, knowledgeBaseId, documentId);
        if (!DOCUMENT_DELETING.equals(document.getStatus())) {
            throw conflict(
                    "DOCUMENT_NOT_DELETING",
                    "Document is not awaiting deletion"
            );
        }
        if (deletionJobMapper.countInProgress(
                tenantId, documentId, JOB_PENDING, JOB_RUNNING
        ) != 0) {
            throw conflict(
                    "DOCUMENT_DELETION_IN_PROGRESS",
                    "A document deletion attempt is already in progress"
            );
        }
        DocumentDeletionJobEntity latest = deletionJobMapper
                .findLatestByTenantAndDocumentForUpdate(tenantId, documentId);
        if (latest == null || !JOB_FAILED.equals(latest.getStatus())) {
            throw conflict(
                    "DOCUMENT_DELETION_NOT_FAILED",
                    "Latest document deletion attempt is not failed"
            );
        }
        if (!Boolean.TRUE.equals(latest.getFailureRetryable())) {
            throw conflict(
                    "DOCUMENT_DELETION_NOT_RETRYABLE",
                    "Latest document deletion failure is not retryable"
            );
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DocumentDeletionJobEntity job = new DocumentDeletionJobEntity(
                null, tenantId, knowledgeBaseId, documentId,
                latest.getAttemptNo() + 1, JOB_PENDING,
                null, null, null, null, null,
                keyHash, fingerprint, adminId,
                null, null, now, now
        );
        insertJobAndOutbox(job, now);
        return accepted(documentId, DOCUMENT_DELETING, job, false);
    }

    private void insertJobAndOutbox(DocumentDeletionJobEntity job, LocalDateTime now) {
        if (deletionJobMapper.insert(job) != 1 || job.getId() == null) {
            throw new IllegalStateException("Document deletion job was not created");
        }
        String payload = """
                {"tenantId":%d,"knowledgeBaseId":%d,"documentId":%d,"documentDeletionJobId":%d}
                """.formatted(
                job.getTenantId(), job.getKnowledgeBaseId(), job.getDocumentId(), job.getId()
        ).strip();
        OutboxEventEntity event = new OutboxEventEntity(
                null, job.getTenantId(), job.getDocumentId(), null, null, job.getId(),
                DELETE_REQUESTED, payload, OUTBOX_PENDING, 0, now,
                null, null, null, null, null, now, now
        );
        if (outboxMapper.insert(event) != 1 || event.getId() == null) {
            throw new IllegalStateException("Document deletion Outbox event was not created");
        }
    }

    private DocumentDeletionAcceptedVO findReplay(
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String keyHash,
            String fingerprint
    ) {
        DocumentDeletionJobEntity existing = deletionJobMapper
                .findByTenantAndIdempotencyHash(tenantId, keyHash);
        if (existing == null) {
            return null;
        }
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key is already bound to another deletion command"
            );
        }
        if (existing.getKnowledgeBaseId() != knowledgeBaseId
                || existing.getDocumentId() != documentId) {
            throw new IllegalStateException("Deletion idempotency fact is inconsistent");
        }
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndId(
                tenantId, knowledgeBaseId, documentId
        );
        if (document == null) {
            throw new IllegalStateException("Deletion document tombstone is missing");
        }
        return accepted(documentId, document.getStatus(), existing, true);
    }

    private DocumentDeletionAcceptedVO accepted(
            long documentId,
            String documentStatus,
            DocumentDeletionJobEntity job,
            boolean replayed
    ) {
        return new DocumentDeletionAcceptedVO(
                documentId, job.getId(), job.getAttemptNo(), documentStatus,
                job.getStatus(), replayed, utc(job.getCreatedAt())
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
        if (principal.tenantId() == null || principal.tenantId() != tenantId) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_SCOPE_FORBIDDEN",
                    "Tenant is outside administrator scope"
            );
        }
        TenantEntity tenant = tenantMapper.findById(tenantId);
        if (tenant == null || !TENANT_ACTIVE.equals(tenant.getStatus())) {
            throw new BusinessException(
                    FORBIDDEN,
                    "TENANT_NOT_ACTIVE",
                    "Tenant is not active"
            );
        }
    }

    private void validateResourceIds(long knowledgeBaseId, long documentId) {
        if (knowledgeBaseId < 1 || documentId < 1) {
            throw validation("KnowledgeBase ID and Document ID must be positive");
        }
    }

    private void requireKnowledgeBaseLocked(long tenantId, long knowledgeBaseId) {
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.findByTenantAndIdForUpdate(
                new TenantResourceQueryDTO(tenantId, knowledgeBaseId)
        );
        if (knowledgeBase == null) {
            throw new BusinessException(
                    NOT_FOUND,
                    "KNOWLEDGE_BASE_NOT_FOUND",
                    "KnowledgeBase was not found"
            );
        }
    }

    private DocumentEntity requireDocumentLocked(
            long tenantId,
            long knowledgeBaseId,
            long documentId
    ) {
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndIdForUpdate(
                tenantId, knowledgeBaseId, documentId
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

    private String validateIdempotencyKey(String value) {
        if (value == null || value.isEmpty() || value.length() > 128
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw validation(
                    "Idempotency-Key must contain 1 to 128 visible ASCII characters"
            );
        }
        return value;
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

    private OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private BusinessException validation(String message) {
        return new BusinessException(VALIDATION, "VALIDATION_FAILED", message);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(CONFLICT, code, message);
    }
}
