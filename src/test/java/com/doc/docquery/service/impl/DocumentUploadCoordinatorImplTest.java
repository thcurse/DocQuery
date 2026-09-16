package com.doc.docquery.service.impl;

import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.dto.CreateDocumentUploadMetadataDTO;
import com.doc.docquery.dto.DocumentRebuildSourceDTO;
import com.doc.docquery.dto.StoredSourceObjectDTO;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.service.DocumentUploadAcceptanceService;
import com.doc.docquery.service.ObjectStorageException;
import com.doc.docquery.service.SourceObjectStore;
import com.doc.docquery.vo.DocumentUploadAcceptedVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionSystemException;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不依赖 Docker 的上传协调器单元测试。
 *
 * <p>这里重点守住对象写入与数据库受理之间的补偿边界；S3 协议和 MySQL
 * 事务本身由 N2.2 集成测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentUploadCoordinatorImplTest {

    private static final long TENANT_ID = 7L;
    private static final long KNOWLEDGE_BASE_ID = 11L;
    private static final String IDEMPOTENCY_KEY = "upload-001";

    @Mock
    private SourceObjectStore objectStore;
    @Mock
    private DocumentUploadAcceptanceService acceptanceService;
    @Mock
    private DocumentVersionMapper documentVersionMapper;

    private DocumentUploadCoordinatorImpl coordinator;
    private AdminPrincipal principal;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setUploadMaxBytes(1024);
        coordinator = new DocumentUploadCoordinatorImpl(
                objectStore,
                properties,
                acceptanceService,
                documentVersionMapper
        );
        principal = new AdminPrincipal(
                3L,
                TENANT_ID,
                "admin",
                null,
                "TENANT_ADMIN",
                true
        );
    }

    @Test
    void storesExactObjectDescriptorBeforeDatabaseAcceptance() {
        byte[] content = "docquery".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.md",
                "text/markdown",
                content
        );
        CreateDocumentUploadMetadataDTO metadata = metadata("Guide");
        DocumentUploadAcceptedVO expected = accepted();

        when(objectStore.bucketName()).thenReturn("source-bucket");
        when(objectStore.put(any(), any(InputStream.class), eq(8L), eq(1024L),
                eq("text/markdown")))
                .thenReturn(new SourceObjectStore.WriteResult(8L, "abc123"));
        when(acceptanceService.acceptNewDocument(
                eq(principal),
                eq(TENANT_ID),
                eq(KNOWLEDGE_BASE_ID),
                any(),
                any()
        )).thenReturn(expected);
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenReturn(1L);

        DocumentUploadAcceptedVO actual = coordinator.uploadNewDocument(
                principal,
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                IDEMPOTENCY_KEY,
                metadata,
                file
        );

        ArgumentCaptor<StoredSourceObjectDTO> sourceCaptor =
                ArgumentCaptor.forClass(StoredSourceObjectDTO.class);
        verify(acceptanceService).acceptNewDocument(
                eq(principal),
                eq(TENANT_ID),
                eq(KNOWLEDGE_BASE_ID),
                any(),
                sourceCaptor.capture()
        );
        StoredSourceObjectDTO source = sourceCaptor.getValue();
        assertThat(actual).isSameAs(expected);
        assertThat(source.getOriginalFilename()).isEqualTo("guide.md");
        assertThat(source.getSourceFormat()).isEqualTo("4");
        assertThat(source.getSourceBucket()).isEqualTo("source-bucket");
        assertThat(source.getSourceObjectKey()).startsWith("source/7/");
        assertThat(source.getSourceSizeBytes()).isEqualTo(8L);
        assertThat(source.getSourceSha256()).isEqualTo("abc123");
        verify(objectStore, never()).delete(any());
    }

    @Test
    void deletesNewRandomObjectWhenIdempotentReplayReturnsOldFacts() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.pdf",
                "application/pdf",
                new byte[]{1, 2, 3}
        );
        when(objectStore.bucketName()).thenReturn("source-bucket");
        when(objectStore.put(any(), any(InputStream.class), eq(3L), eq(1024L),
                eq("application/pdf")))
                .thenReturn(new SourceObjectStore.WriteResult(3L, "sha"));
        when(acceptanceService.acceptNewDocument(any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(accepted());
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenReturn(0L);

        coordinator.uploadNewDocument(
                principal,
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                IDEMPOTENCY_KEY,
                metadata("Guide"),
                file
        );

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStore).delete(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("source/7/");
    }

    @Test
    void rejectsOversizedFileBeforeCallingObjectStorage() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.txt",
                "text/plain",
                new byte[1025]
        );

        assertThatThrownBy(() -> coordinator.uploadNewDocument(
                principal,
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                IDEMPOTENCY_KEY,
                metadata("Large"),
                file
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.failure()).isEqualTo(
                    BusinessException.Failure.TOO_LARGE
            );
            assertThat(exception.code()).isEqualTo("FILE_TOO_LARGE");
        });

        verify(objectStore, never()).put(any(), any(), anyLong(), anyLong(), any());
        verify(acceptanceService, never()).acceptNewDocument(
                any(), anyLong(), anyLong(), any(), any()
        );
    }

    @Test
    void mapsStorageSdkFailureToStableServiceUnavailableError() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1}
        );
        when(objectStore.put(any(), any(), eq(1L), eq(1024L), any()))
                .thenThrow(new ObjectStorageException(
                        ObjectStorageException.Reason.UNAVAILABLE,
                        "credential-bearing SDK detail"
                ));

        assertThatThrownBy(() -> coordinator.uploadNewDocument(
                principal,
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                IDEMPOTENCY_KEY,
                metadata("Guide"),
                file
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.failure()).isEqualTo(
                    BusinessException.Failure.UNAVAILABLE
            );
            assertThat(exception.code()).isEqualTo("OBJECT_STORAGE_UNAVAILABLE");
            assertThat(exception.getMessage()).doesNotContain("credential");
        });
        verify(objectStore).delete(any());
    }

    @Test
    void rebuildCopiesExactActiveSourceBeforeAcceptance() {
        byte[] content = "same-source".getBytes();
        DocumentRebuildSourceDTO source = new DocumentRebuildSourceDTO(
                31L,
                "guide.pdf",
                "1",
                "source-bucket",
                "source/old-key",
                content.length,
                "same-sha",
                "application/pdf"
        );
        DocumentUploadAcceptedVO expected = accepted();
        when(acceptanceService.loadRebuildSource(
                principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L
        )).thenReturn(source);
        when(objectStore.bucketName()).thenReturn("source-bucket");
        when(objectStore.open("source/old-key"))
                .thenReturn(new ByteArrayInputStream(content));
        when(objectStore.put(any(), any(InputStream.class), eq((long) content.length),
                eq(1024L), eq("application/pdf")))
                .thenReturn(new SourceObjectStore.WriteResult(content.length, "same-sha"));
        when(acceptanceService.acceptRebuild(
                eq(principal), eq(TENANT_ID), eq(KNOWLEDGE_BASE_ID), eq(21L),
                eq(31L), eq(IDEMPOTENCY_KEY), any()
        )).thenReturn(expected);
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenReturn(1L);

        DocumentUploadAcceptedVO actual = coordinator.rebuildDocument(
                principal,
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                21L,
                IDEMPOTENCY_KEY
        );

        ArgumentCaptor<StoredSourceObjectDTO> copied =
                ArgumentCaptor.forClass(StoredSourceObjectDTO.class);
        verify(acceptanceService).acceptRebuild(
                eq(principal), eq(TENANT_ID), eq(KNOWLEDGE_BASE_ID), eq(21L),
                eq(31L), eq(IDEMPOTENCY_KEY), copied.capture()
        );
        assertThat(actual).isSameAs(expected);
        assertThat(copied.getValue().getSourceObjectKey()).startsWith("source/7/");
        assertThat(copied.getValue().getSourceSha256()).isEqualTo("same-sha");
        assertThat(copied.getValue().getSourceSizeBytes()).isEqualTo(content.length);
        verify(objectStore, never()).delete(any());
    }

    @Test
    void rebuildReplayDoesNotReadOrCopySourceObject() {
        DocumentUploadAcceptedVO replay = accepted();
        when(acceptanceService.findRebuildReplay(
                principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L, IDEMPOTENCY_KEY
        )).thenReturn(replay);

        assertThat(coordinator.rebuildDocument(
                principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L, IDEMPOTENCY_KEY
        )).isSameAs(replay);

        verify(acceptanceService, never()).loadRebuildSource(
                any(), anyLong(), anyLong(), anyLong()
        );
        verify(objectStore, never()).open(any());
        verify(objectStore, never()).put(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void rebuildRejectsAndDeletesCopyWhenDigestChanges() {
        byte[] content = "same-source".getBytes();
        when(acceptanceService.loadRebuildSource(
                principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L
        )).thenReturn(new DocumentRebuildSourceDTO(
                31L, "guide.pdf", "1", "source-bucket", "source/old-key",
                content.length, "expected-sha", "application/pdf"
        ));
        when(objectStore.bucketName()).thenReturn("source-bucket");
        when(objectStore.open("source/old-key"))
                .thenReturn(new ByteArrayInputStream(content));
        when(objectStore.put(any(), any(), eq((long) content.length), eq(1024L), any()))
                .thenReturn(new SourceObjectStore.WriteResult(content.length, "wrong-sha"));

        assertThatThrownBy(() -> coordinator.rebuildDocument(
                principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L, IDEMPOTENCY_KEY
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo(
                        "DOCUMENT_REBUILD_SOURCE_UNAVAILABLE"
                ));

        verify(objectStore).delete(any());
        verify(acceptanceService, never()).acceptRebuild(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()
        );
    }

    @ParameterizedTest(name = "reference lookup failure after acceptance, rebuild={0}")
    @ValueSource(booleans = {false, true})
    void preservesAcceptedObjectAndResponseWhenReferenceLookupFails(boolean rebuild) {
        Supplier<DocumentUploadAcceptedVO> upload = prepareUpload(
                rebuild, new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        DocumentUploadAcceptedVO expected = accepted();
        stubAcceptance(rebuild).thenReturn(expected);
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(upload.get()).isSameAs(expected);

        verify(objectStore, never()).delete(any());
    }

    @ParameterizedTest(name = "input close failure before acceptance, rebuild={0}")
    @ValueSource(booleans = {false, true})
    void closesInputBeforeAcceptanceAndOnlyDeletesUnacceptedObject(boolean rebuild) {
        InputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public void close() throws IOException {
                throw new IOException("input close failed");
            }
        };
        Supplier<DocumentUploadAcceptedVO> upload = prepareUpload(rebuild, input);

        assertThatThrownBy(upload::get)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("OBJECT_STORAGE_UNAVAILABLE"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStore).put(keyCaptor.capture(), any(), anyLong(), anyLong(), any());
        verify(objectStore).delete(keyCaptor.getValue());
        verify(acceptanceService, never()).acceptNewDocument(
                any(), anyLong(), anyLong(), any(), any()
        );
        verify(acceptanceService, never()).acceptRebuild(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()
        );
    }

    @ParameterizedTest(name = "unknown commit outcome, rebuild={0}")
    @ValueSource(booleans = {false, true})
    void preservesObjectWhenAcceptanceCommitOutcomeIsUnknown(boolean rebuild) {
        Supplier<DocumentUploadAcceptedVO> upload = prepareUpload(
                rebuild, new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        TransactionSystemException failure = new TransactionSystemException(
                "connection lost while committing"
        );
        stubAcceptance(rebuild).thenThrow(failure);

        assertThatThrownBy(upload::get).isSameAs(failure);

        verify(objectStore, never()).delete(any());
        verify(documentVersionMapper, never()).countBySourceObject(any(), any());
    }

    @ParameterizedTest(name = "business rejection cleanup, rebuild={0}")
    @ValueSource(booleans = {false, true})
    void deletesUnreferencedObjectAfterBusinessRejection(boolean rebuild) {
        Supplier<DocumentUploadAcceptedVO> upload = prepareUpload(
                rebuild, new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        BusinessException rejection = new BusinessException(
                BusinessException.Failure.CONFLICT, "DOCUMENT_UPLOAD_CONFLICT", "Conflict"
        );
        stubAcceptance(rebuild).thenThrow(rejection);
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenReturn(0L);

        assertThatThrownBy(upload::get).isSameAs(rejection);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStore).put(keyCaptor.capture(), any(), anyLong(), anyLong(), any());
        verify(objectStore).delete(keyCaptor.getValue());
    }

    @Test
    void preservesReferencedObjectAfterBusinessRejection() {
        Supplier<DocumentUploadAcceptedVO> upload = prepareUpload(
                false, new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        BusinessException rejection = new BusinessException(
                BusinessException.Failure.CONFLICT, "SOURCE_OBJECT_CONFLICT", "Conflict"
        );
        stubAcceptance(false).thenThrow(rejection);
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenReturn(1L);

        assertThatThrownBy(upload::get).isSameAs(rejection);

        verify(objectStore, never()).delete(any());
    }

    @Test
    void rebuildDeletesUnreferencedCopyWhenConcurrentRequestWins() {
        Supplier<DocumentUploadAcceptedVO> upload = prepareUpload(
                true, new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        DocumentUploadAcceptedVO replay = accepted();
        stubAcceptance(true).thenReturn(replay);
        when(documentVersionMapper.countBySourceObject(eq("source-bucket"), any()))
                .thenReturn(0L);

        assertThat(upload.get()).isSameAs(replay);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStore).put(keyCaptor.capture(), any(), anyLong(), anyLong(), any());
        verify(objectStore).delete(keyCaptor.getValue());
    }

    private Supplier<DocumentUploadAcceptedVO> prepareUpload(boolean rebuild, InputStream input) {
        when(objectStore.bucketName()).thenReturn("source-bucket");
        when(objectStore.put(any(), any(InputStream.class), eq(3L), eq(1024L),
                eq("application/pdf")))
                .thenReturn(new SourceObjectStore.WriteResult(3L, "same-sha"));
        if (rebuild) {
            when(acceptanceService.loadRebuildSource(
                    principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L
            )).thenReturn(new DocumentRebuildSourceDTO(
                    31L, "guide.pdf", "1", "source-bucket", "source/old-key",
                    3L, "same-sha", "application/pdf"
            ));
            when(objectStore.open("source/old-key")).thenReturn(input);
            return () -> coordinator.rebuildDocument(
                    principal, TENANT_ID, KNOWLEDGE_BASE_ID, 21L, IDEMPOTENCY_KEY
            );
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.pdf", "application/pdf", new byte[]{1, 2, 3}
        ) {
            @Override
            public InputStream getInputStream() {
                return input;
            }
        };
        return () -> coordinator.uploadNewDocument(
                principal, TENANT_ID, KNOWLEDGE_BASE_ID,
                IDEMPOTENCY_KEY, metadata("Guide"), file
        );
    }

    private OngoingStubbing<DocumentUploadAcceptedVO> stubAcceptance(boolean rebuild) {
        if (rebuild) {
            return when(acceptanceService.acceptRebuild(
                    any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()
            ));
        }
        return when(acceptanceService.acceptNewDocument(
                any(), anyLong(), anyLong(), any(), any()
        ));
    }

    private CreateDocumentUploadMetadataDTO metadata(String documentName) {
        CreateDocumentUploadMetadataDTO metadata = new CreateDocumentUploadMetadataDTO();
        metadata.setDocumentName(documentName);
        return metadata;
    }

    private DocumentUploadAcceptedVO accepted() {
        return new DocumentUploadAcceptedVO(
                21L,
                22L,
                1,
                23L,
                "ENABLED",
                "PROCESSING",
                "PENDING",
                OffsetDateTime.parse("2026-08-03T00:00:00Z")
        );
    }
}
