package com.doc.docquery.service.impl;

import com.doc.docquery.audit.QueryAuditStart;
import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.entity.ApplicationQueryAuditEntity;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.service.AnswerService;
import com.doc.docquery.service.ApplicationCredentialResolver;
import com.doc.docquery.service.QueryAuditService;
import com.doc.docquery.service.RetrieveService;
import com.doc.docquery.vo.RetrieveResponseVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.doc.docquery.exception.BusinessException.Failure.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditedQueryServiceImplTest {

    private static final String TOKEN = "dq_app_" + "K".repeat(22) + "." + "s".repeat(43);

    @Test
    void startAuditFailurePreventsQueryExecution() {
        ApplicationCredentialResolver resolver = mock(ApplicationCredentialResolver.class);
        QueryAuditService audit = mock(QueryAuditService.class);
        RetrieveService retrieve = mock(RetrieveService.class);
        AnswerService answer = mock(AnswerService.class);
        when(resolver.resolve(TOKEN)).thenReturn(new ApplicationCredentialPrincipal(1L, 2L, 3L));
        when(audit.start(any(QueryAuditStart.class))).thenThrow(unavailable());
        AuditedQueryServiceImpl service = new AuditedQueryServiceImpl(
                resolver, audit, retrieve, answer
        );

        BusinessException failure = catchThrowableOfType(
                BusinessException.class,
                () -> service.retrieve(
                        "00000000-0000-0000-0000-000000000001",
                        "Bearer " + TOKEN,
                        "key",
                        null,
                        null,
                        9L,
                        request()
                )
        );

        assertThat(failure.code()).isEqualTo("QUERY_AUDIT_UNAVAILABLE");
        verify(retrieve, never()).retrieve(
                any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any()
        );
    }

    @Test
    void terminalAuditFailureSuppressesSuccessfulResponse() {
        ApplicationCredentialResolver resolver = mock(ApplicationCredentialResolver.class);
        QueryAuditService audit = mock(QueryAuditService.class);
        RetrieveService retrieve = mock(RetrieveService.class);
        AnswerService answer = mock(AnswerService.class);
        when(resolver.resolve(TOKEN)).thenReturn(new ApplicationCredentialPrincipal(1L, 2L, 3L));
        ApplicationQueryAuditEntity started = new ApplicationQueryAuditEntity();
        started.setId(10L);
        started.setStartedAt(LocalDateTime.now());
        when(audit.start(any(QueryAuditStart.class))).thenReturn(started);
        when(retrieve.retrieve(
                any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any()
        )).thenReturn(new RetrieveResponseVO(
                "execution", 9L, "KEYWORD", "KEYWORD", false, null, List.of()
        ));
        doThrow(unavailable()).when(audit).succeed(any(), any());
        AuditedQueryServiceImpl service = new AuditedQueryServiceImpl(
                resolver, audit, retrieve, answer
        );

        BusinessException failure = catchThrowableOfType(
                BusinessException.class,
                () -> service.retrieve(
                        "00000000-0000-0000-0000-000000000002",
                        "Bearer " + TOKEN,
                        "key",
                        null,
                        null,
                        9L,
                        request()
                )
        );

        assertThat(failure.code()).isEqualTo("QUERY_AUDIT_UNAVAILABLE");
        verify(audit).fail(any(), any(), any());
    }

    @Test
    void trustedCredentialWithInvalidAuditHeaderIsRecordedAsRejected() {
        ApplicationCredentialResolver resolver = mock(ApplicationCredentialResolver.class);
        QueryAuditService audit = mock(QueryAuditService.class);
        RetrieveService retrieve = mock(RetrieveService.class);
        AnswerService answer = mock(AnswerService.class);
        when(resolver.resolve(TOKEN)).thenReturn(new ApplicationCredentialPrincipal(1L, 2L, 3L));
        ApplicationQueryAuditEntity started = new ApplicationQueryAuditEntity();
        started.setId(10L);
        started.setStartedAt(LocalDateTime.now());
        when(audit.start(any(QueryAuditStart.class))).thenReturn(started);
        AuditedQueryServiceImpl service = new AuditedQueryServiceImpl(
                resolver, audit, retrieve, answer
        );

        catchThrowableOfType(
                RuntimeException.class,
                () -> service.retrieve(
                        "00000000-0000-0000-0000-000000000003",
                        "Bearer " + TOKEN,
                        "key",
                        "包含中文的非法追踪标识",
                        null,
                        9L,
                        request()
                )
        );

        verify(audit).start(any(QueryAuditStart.class));
        verify(audit).fail(any(), any(), any());
        verify(retrieve, never()).retrieve(
                any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any()
        );
    }

    private RetrieveRequestDTO request() {
        RetrieveRequestDTO request = new RetrieveRequestDTO();
        request.setQuery("refund");
        request.setMode("KEYWORD");
        request.setTopK(1);
        return request;
    }

    private BusinessException unavailable() {
        return new BusinessException(
                UNAVAILABLE,
                "QUERY_AUDIT_UNAVAILABLE",
                "Query audit is unavailable"
        );
    }
}
