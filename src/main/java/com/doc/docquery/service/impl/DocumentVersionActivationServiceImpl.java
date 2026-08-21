package com.doc.docquery.service.impl;

import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentSearchProjectionEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.enums.DocumentVersionStatus;
import com.doc.docquery.enums.ProcessingJobStatus;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentSearchProjectionMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.service.DocumentIngestionProcessor;
import com.doc.docquery.service.DocumentProcessingException;
import com.doc.docquery.service.DocumentVersionActivationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** N2.5 唯一允许修改 activeVersionId 的事务服务。 */
@Service
public class DocumentVersionActivationServiceImpl
        implements DocumentVersionActivationService {

    private static final String DOCUMENT_ACTIVE = DocumentStatus.ACTIVE.getCode();
    private static final String VERSION_PROCESSING =
            DocumentVersionStatus.PROCESSING.getCode();
    private static final String VERSION_READY = DocumentVersionStatus.READY.getCode();
    private static final String JOB_RUNNING = ProcessingJobStatus.RUNNING.getCode();
    private static final String JOB_SUCCEEDED = ProcessingJobStatus.SUCCEEDED.getCode();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final ProcessingJobMapper jobMapper;
    private final DocumentSearchProjectionMapper projectionMapper;
    private final TransactionTemplate transaction;

    public DocumentVersionActivationServiceImpl(
            DocumentMapper documentMapper,
            DocumentVersionMapper versionMapper,
            ProcessingJobMapper jobMapper,
            DocumentSearchProjectionMapper projectionMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.jobMapper = jobMapper;
        this.projectionMapper = projectionMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void activate(DocumentIngestionProcessor.Context context) {
        transaction.executeWithoutResult(status -> activateLocked(context));
    }

    private void activateLocked(DocumentIngestionProcessor.Context context) {
        long tenantId = context.message().tenantId();
        long knowledgeBaseId = context.message().knowledgeBaseId();
        long documentId = context.message().documentId();
        long versionId = context.message().documentVersionId();
        long jobId = context.message().processingJobId();

        // 固定加锁顺序，避免并发版本受理和最终激活形成循环等待。
        DocumentEntity document = documentMapper.findByTenantKnowledgeBaseAndIdForUpdate(
                tenantId,
                knowledgeBaseId,
                documentId
        );
        DocumentVersionEntity version = versionMapper
                .findByTenantDocumentAndIdForUpdate(tenantId, documentId, versionId);
        ProcessingJobEntity job = jobMapper.findByIdForUpdate(jobId);
        DocumentSearchProjectionEntity projection = projectionMapper
                .findByDocumentVersionIdForUpdate(versionId);
        validate(context, document, version, job, projection);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        requireUpdated(versionMapper.markReady(
                versionId,
                now,
                VERSION_PROCESSING,
                VERSION_READY
        ));
        requireUpdated(documentMapper.activateLatestVersion(
                tenantId,
                knowledgeBaseId,
                documentId,
                versionId,
                DOCUMENT_ACTIVE,
                now
        ));
        requireUpdated(jobMapper.markSucceeded(
                jobId,
                context.leaseOwner(),
                now,
                JOB_RUNNING,
                JOB_SUCCEEDED
        ));
    }

    private void validate(
            DocumentIngestionProcessor.Context context,
            DocumentEntity document,
            DocumentVersionEntity version,
            ProcessingJobEntity job,
            DocumentSearchProjectionEntity projection
    ) {
        if (document == null
                || version == null
                || job == null
                || projection == null
                || context.leaseOwner() == null
                || context.leaseOwner().isBlank()
                || !DOCUMENT_ACTIVE.equals(document.getStatus())
                || !version.getId().equals(document.getLatestVersionId())
                || !VERSION_PROCESSING.equals(version.getStatus())
                || !job.getTenantId().equals(version.getTenantId())
                || !job.getDocumentVersionId().equals(version.getId())
                || !JOB_RUNNING.equals(job.getStatus())
                || !context.leaseOwner().equals(job.getLeaseOwner())
                || !projection.getTenantId().equals(version.getTenantId())
                || !projection.getKnowledgeBaseId().equals(document.getKnowledgeBaseId())
                || !projection.getDocumentId().equals(document.getId())
                || !projection.getDocumentVersionId().equals(version.getId())
                || !projection.getEvidenceExpectedCount().equals(
                projection.getEvidenceActualCount())
                || !projection.getNavigationExpectedCount().equals(
                projection.getNavigationActualCount())) {
            throw conflict();
        }
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw conflict();
        }
    }

    private DocumentProcessingException conflict() {
        return new DocumentProcessingException(
                "SEARCH_ACTIVATION_CONFLICT",
                "Document version activation facts do not match",
                false
        );
    }
}
