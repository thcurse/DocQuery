package com.doc.docquery.service.impl;

import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentSearchProjectionEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.mapper.DocumentMapper;
import com.doc.docquery.mapper.DocumentSearchProjectionMapper;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.mapper.ProcessingJobMapper;
import com.doc.docquery.messaging.DocumentProcessingMessage;
import com.doc.docquery.service.DocumentIngestionProcessor;
import com.doc.docquery.service.DocumentProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 不依赖容器验证最终激活的条件写与租约所有权边界。 */
class DocumentVersionActivationServiceImplTest {

    private DocumentMapper documentMapper;
    private DocumentVersionMapper versionMapper;
    private ProcessingJobMapper jobMapper;
    private DocumentSearchProjectionMapper projectionMapper;
    private PlatformTransactionManager transactionManager;
    private DocumentVersionActivationServiceImpl service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        versionMapper = mock(DocumentVersionMapper.class);
        jobMapper = mock(ProcessingJobMapper.class);
        projectionMapper = mock(DocumentSearchProjectionMapper.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new DocumentVersionActivationServiceImpl(
                documentMapper,
                versionMapper,
                jobMapper,
                projectionMapper,
                transactionManager
        );
    }

    @Test
    void commitsReadyActiveAndSucceededWhenAllLockedFactsMatch() {
        Fixture fixture = matchingFixture("lease-a");
        when(versionMapper.markReady(eq(4L), any(), eq("1"), eq("2"))).thenReturn(1);
        when(documentMapper.activateLatestVersion(
                eq(1L), eq(2L), eq(3L), eq(4L), eq("1"), any()
        )).thenReturn(1);
        when(jobMapper.markSucceeded(eq(5L), eq("lease-a"), any(), eq("2"), eq("3")))
                .thenReturn(1);

        service.activate(fixture.context());

        verify(versionMapper).markReady(eq(4L), any(), eq("1"), eq("2"));
        verify(documentMapper).activateLatestVersion(
                eq(1L), eq(2L), eq(3L), eq(4L), eq("1"), any()
        );
        verify(jobMapper).markSucceeded(
                eq(5L), eq("lease-a"), any(), eq("2"), eq("3")
        );
        verify(transactionManager).commit(any());
    }

    @Test
    void rejectsStaleLeaseBeforeAnyLifecycleUpdate() {
        Fixture fixture = matchingFixture("lease-a");
        DocumentIngestionProcessor.Context stale = new DocumentIngestionProcessor.Context(
                fixture.context().message(),
                fixture.context().document(),
                fixture.context().version(),
                fixture.context().job(),
                "lease-b"
        );

        DocumentProcessingException failure = catchThrowableOfType(
                DocumentProcessingException.class,
                () -> service.activate(stale)
        );

        assertThat(failure.code()).isEqualTo("SEARCH_ACTIVATION_CONFLICT");
        assertThat(failure.retryable()).isFalse();
        verify(versionMapper, never()).markReady(any(), any(), any(), any());
        verify(documentMapper, never()).activateLatestVersion(
                any(), any(), any(), any(), any(), any()
        );
        verify(jobMapper, never()).markSucceeded(any(), any(), any(), any(), any());
        verify(transactionManager).rollback(any());
    }

    private Fixture matchingFixture(String leaseOwner) {
        DocumentEntity document = new DocumentEntity();
        document.setId(3L);
        document.setTenantId(1L);
        document.setKnowledgeBaseId(2L);
        document.setStatus("1");
        document.setLatestVersionId(4L);

        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setId(4L);
        version.setTenantId(1L);
        version.setDocumentId(3L);
        version.setStatus("1");

        ProcessingJobEntity job = new ProcessingJobEntity();
        job.setId(5L);
        job.setTenantId(1L);
        job.setDocumentVersionId(4L);
        job.setStatus("2");
        job.setLeaseOwner(leaseOwner);

        DocumentSearchProjectionEntity projection = new DocumentSearchProjectionEntity();
        projection.setTenantId(1L);
        projection.setKnowledgeBaseId(2L);
        projection.setDocumentId(3L);
        projection.setDocumentVersionId(4L);
        projection.setEvidenceExpectedCount(6);
        projection.setEvidenceActualCount(6);
        projection.setNavigationExpectedCount(2);
        projection.setNavigationActualCount(2);

        when(documentMapper.findByTenantKnowledgeBaseAndIdForUpdate(1L, 2L, 3L))
                .thenReturn(document);
        when(versionMapper.findByTenantDocumentAndIdForUpdate(1L, 3L, 4L))
                .thenReturn(version);
        when(jobMapper.findByIdForUpdate(5L)).thenReturn(job);
        when(projectionMapper.findByDocumentVersionIdForUpdate(4L))
                .thenReturn(projection);

        DocumentProcessingMessage message = new DocumentProcessingMessage(
                1, 9, "1", 1, 2, 3, 4, 5
        );
        return new Fixture(new DocumentIngestionProcessor.Context(
                message,
                document,
                version,
                job,
                leaseOwner
        ));
    }

    private record Fixture(DocumentIngestionProcessor.Context context) {
    }
}
