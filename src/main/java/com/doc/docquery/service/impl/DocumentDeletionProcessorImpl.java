package com.doc.docquery.service.impl;

import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;
import com.doc.docquery.entity.DocumentDeletionJobEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.enums.DocumentDeletionJobStatus;
import com.doc.docquery.enums.DocumentStatus;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.mapper.DocumentDeletionJobMapper;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentRetrievalArtifactMapper;
import com.doc.docquery.mapper.DocumentSearchProjectionMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.search.SearchProjectionScope;
import com.doc.docquery.search.SearchProjectionStore;
import com.doc.docquery.search.SearchProjectionException;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.DocumentDeletionException;
import com.doc.docquery.service.DocumentDeletionProcessor;
import com.doc.docquery.service.RetrievalArtifactStore;
import com.doc.docquery.service.SourceObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** N4.2 幂等删除 ES、三类对象，最后在短事务中提交 MySQL 墓碑。 */
@Service
@ConditionalOnProperty(prefix = "docquery.search", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DocumentDeletionProcessorImpl implements DocumentDeletionProcessor {

    private static final String DELETING = DocumentStatus.DELETING.getCode();
    private static final String DELETED = DocumentStatus.DELETED.getCode();
    private static final String RUNNING = DocumentDeletionJobStatus.RUNNING.getCode();
    private static final String SUCCEEDED = DocumentDeletionJobStatus.SUCCEEDED.getCode();

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final DocumentDeletionJobMapper deletionJobMapper;
    private final DocumentCanonicalArtifactMapper canonicalArtifactMapper;
    private final DocumentRetrievalArtifactMapper retrievalArtifactMapper;
    private final DocumentSearchProjectionMapper projectionMapper;
    private final SearchProjectionStore searchStore;
    private final SourceObjectStore sourceStore;
    private final CanonicalArtifactStore canonicalStore;
    private final RetrievalArtifactStore retrievalStore;
    private final ObjectStorageProperties objectStorageProperties;
    private final DocumentParsingProperties parsingProperties;
    private final DocumentRetrievalProperties retrievalProperties;
    private final TransactionTemplate transaction;

    public DocumentDeletionProcessorImpl(
            DocumentMapper documentMapper,
            DocumentVersionMapper versionMapper,
            DocumentDeletionJobMapper deletionJobMapper,
            DocumentCanonicalArtifactMapper canonicalArtifactMapper,
            DocumentRetrievalArtifactMapper retrievalArtifactMapper,
            DocumentSearchProjectionMapper projectionMapper,
            SearchProjectionStore searchStore,
            SourceObjectStore sourceStore,
            CanonicalArtifactStore canonicalStore,
            RetrievalArtifactStore retrievalStore,
            ObjectStorageProperties objectStorageProperties,
            DocumentParsingProperties parsingProperties,
            DocumentRetrievalProperties retrievalProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.deletionJobMapper = deletionJobMapper;
        this.canonicalArtifactMapper = canonicalArtifactMapper;
        this.retrievalArtifactMapper = retrievalArtifactMapper;
        this.projectionMapper = projectionMapper;
        this.searchStore = searchStore;
        this.sourceStore = sourceStore;
        this.canonicalStore = canonicalStore;
        this.retrievalStore = retrievalStore;
        this.objectStorageProperties = objectStorageProperties;
        this.parsingProperties = parsingProperties;
        this.retrievalProperties = retrievalProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void process(Context context) {
        try {
            validateContext(context);
            List<DocumentVersionEntity> versions = versionMapper.findByTenantAndDocument(
                    context.document().getTenantId(), context.document().getId()
            );
            if (versions.isEmpty()) {
                throw deterministic("DOCUMENT_VERSION_FACTS_MISSING");
            }
            validateVersions(context.document(), versions);
            searchStore.ensureIndices();
            for (DocumentVersionEntity version : versions) {
                cleanupVersion(context.document(), version);
            }
            searchStore.refresh();
            commitSuccess(context, versions);
        } catch (DocumentDeletionException exception) {
            throw exception;
        } catch (SearchProjectionException exception) {
            throw new DocumentDeletionException(
                    exception.code(),
                    "Document search projection deletion failed",
                    exception.retryable(),
                    exception
            );
        } catch (RuntimeException exception) {
            throw new DocumentDeletionException(
                    "DOCUMENT_DELETION_DEPENDENCY_UNAVAILABLE",
                    "Document deletion dependency is unavailable",
                    true,
                    exception
            );
        }
    }

    private void cleanupVersion(DocumentEntity document, DocumentVersionEntity version) {
        searchStore.deleteVersion(new SearchProjectionScope(
                document.getTenantId(),
                document.getKnowledgeBaseId(),
                document.getId(),
                version.getId(),
                null
        ));

        DocumentCanonicalArtifactEntity canonical = canonicalArtifactMapper
                .findByDocumentVersionId(version.getId());
        if (canonical != null) {
            requireArtifactScope(
                    canonical.getTenantId(), canonical.getDocumentVersionId(),
                    document, version
            );
            requireBucket(canonical.getCanonicalBucket(), canonicalStore.bucketName());
            requireObjectKey(
                    canonical.getCanonicalObjectKey(),
                    versionPrefix(
                            parsingProperties.getCanonicalPrefix(), document, version
                    )
            );
            canonicalStore.delete(canonical.getCanonicalObjectKey());
        }

        DocumentRetrievalArtifactEntity retrieval = retrievalArtifactMapper
                .findByDocumentVersionId(version.getId());
        if (retrieval != null) {
            requireArtifactScope(
                    retrieval.getTenantId(), retrieval.getDocumentVersionId(),
                    document, version
            );
            requireBucket(retrieval.getRetrievalBucket(), retrievalStore.bucketName());
            requireObjectKey(
                    retrieval.getRetrievalObjectKey(),
                    versionPrefix(
                            retrievalProperties.getRetrievalPrefix(), document, version
                    )
            );
            retrievalStore.delete(retrieval.getRetrievalObjectKey());
        }

        requireBucket(version.getSourceBucket(), sourceStore.bucketName());
        requireObjectKey(
                version.getSourceObjectKey(),
                tenantPrefix(objectStorageProperties.getOrphanPrefix(), document)
        );
        sourceStore.delete(version.getSourceObjectKey());
    }

    private void commitSuccess(Context context, List<DocumentVersionEntity> versions) {
        transaction.executeWithoutResult(status -> {
            DocumentEntity lockedDocument = documentMapper
                    .findByTenantKnowledgeBaseAndIdForUpdate(
                            context.document().getTenantId(),
                            context.document().getKnowledgeBaseId(),
                            context.document().getId()
                    );
            DocumentDeletionJobEntity lockedJob = deletionJobMapper.findByIdForUpdate(
                    context.job().getId()
            );
            if (lockedDocument == null || lockedJob == null
                    || !DELETING.equals(lockedDocument.getStatus())
                    || !RUNNING.equals(lockedJob.getStatus())
                    || !context.leaseOwner().equals(lockedJob.getLeaseOwner())
                    || !lockedJob.getTenantId().equals(lockedDocument.getTenantId())
                    || !lockedJob.getKnowledgeBaseId().equals(
                            lockedDocument.getKnowledgeBaseId())
                    || !lockedJob.getDocumentId().equals(lockedDocument.getId())) {
                throw deterministic("DOCUMENT_DELETION_FACTS_CHANGED");
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            List<Long> versionIds = versions.stream().map(DocumentVersionEntity::getId).toList();
            projectionMapper.deleteByDocument(
                    lockedDocument.getTenantId(),
                    lockedDocument.getKnowledgeBaseId(),
                    lockedDocument.getId()
            );
            retrievalArtifactMapper.deleteByTenantAndVersions(
                    lockedDocument.getTenantId(), versionIds
            );
            canonicalArtifactMapper.deleteByTenantAndVersions(
                    lockedDocument.getTenantId(), versionIds
            );
            versionMapper.markContentDeletedByDocument(
                    lockedDocument.getTenantId(), lockedDocument.getId(), now
            );
            if (deletionJobMapper.markSucceeded(
                    lockedJob.getId(), context.leaseOwner(), now, RUNNING, SUCCEEDED
            ) != 1 || documentMapper.completeDeletion(
                    lockedDocument.getTenantId(),
                    lockedDocument.getKnowledgeBaseId(),
                    lockedDocument.getId(),
                    DELETING,
                    DELETED,
                    now
            ) != 1) {
                throw deterministic("DOCUMENT_DELETION_FACTS_CHANGED");
            }
        });
    }

    private void validateContext(Context context) {
        if (context == null || context.message() == null || context.document() == null
                || context.job() == null || context.leaseOwner() == null
                || !DELETING.equals(context.document().getStatus())
                || !RUNNING.equals(context.job().getStatus())) {
            throw deterministic("DOCUMENT_DELETION_FACTS_CHANGED");
        }
    }

    private void validateVersions(
            DocumentEntity document,
            List<DocumentVersionEntity> versions
    ) {
        for (DocumentVersionEntity version : versions) {
            if (!version.getTenantId().equals(document.getTenantId())
                    || !version.getDocumentId().equals(document.getId())) {
                throw deterministic("DOCUMENT_VERSION_SCOPE_MISMATCH");
            }
        }
    }

    private void requireBucket(String actual, String expected) {
        if (actual == null || !actual.equals(expected)) {
            throw deterministic("DOCUMENT_OBJECT_SCOPE_MISMATCH");
        }
    }

    private void requireArtifactScope(
            Long artifactTenantId,
            Long artifactVersionId,
            DocumentEntity document,
            DocumentVersionEntity version
    ) {
        if (!document.getTenantId().equals(artifactTenantId)
                || !version.getId().equals(artifactVersionId)) {
            throw deterministic("DOCUMENT_OBJECT_SCOPE_MISMATCH");
        }
    }

    private void requireObjectKey(String value, String requiredPrefix) {
        if (value == null || value.isBlank() || !value.startsWith(requiredPrefix)) {
            throw deterministic("DOCUMENT_OBJECT_SCOPE_MISMATCH");
        }
    }

    private String tenantPrefix(String prefix, DocumentEntity document) {
        return normalizedPrefix(prefix) + document.getTenantId() + "/";
    }

    private String versionPrefix(
            String prefix,
            DocumentEntity document,
            DocumentVersionEntity version
    ) {
        return tenantPrefix(prefix, document) + version.getId() + "/";
    }

    private String normalizedPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw deterministic("DOCUMENT_OBJECT_SCOPE_MISMATCH");
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private DocumentDeletionException deterministic(String code) {
        return new DocumentDeletionException(
                code,
                "Document deletion facts are inconsistent",
                false,
                null
        );
    }
}
