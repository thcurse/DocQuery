package com.doc.docquery.service.impl;

import com.doc.docquery.audit.AuditedQueryResult;
import com.doc.docquery.audit.QueryAuditFailureClassifier;
import com.doc.docquery.audit.QueryAuditOperation;
import com.doc.docquery.audit.QueryAuditStart;
import com.doc.docquery.audit.QueryExecutionTelemetry;
import com.doc.docquery.dto.AnswerRequestDTO;
import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.entity.ApplicationQueryAuditEntity;
import com.doc.docquery.security.ApplicationCredentialAuthenticationException;
import com.doc.docquery.security.ApplicationCredentialPrincipal;
import com.doc.docquery.security.QueryAccessException;
import com.doc.docquery.service.AnswerException;
import com.doc.docquery.service.AnswerService;
import com.doc.docquery.service.ApplicationCredentialResolver;
import com.doc.docquery.service.AuditedQueryService;
import com.doc.docquery.service.QueryAuditService;
import com.doc.docquery.service.RetrieveException;
import com.doc.docquery.service.RetrieveService;
import com.doc.docquery.vo.AnswerResponseVO;
import com.doc.docquery.vo.RetrieveResponseVO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Function;

import static com.doc.docquery.security.QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID;

/** 先建立可信审计主体，再复用已验收的N3.2/N3.3服务，不改变其权限语义。 */
@Service
public class AuditedQueryServiceImpl implements AuditedQueryService {

    private static final String BEARER_PREFIX = "bearer ";

    private final ApplicationCredentialResolver credentialResolver;
    private final QueryAuditService auditService;
    private final RetrieveService retrieveService;
    private final AnswerService answerService;

    public AuditedQueryServiceImpl(
            ApplicationCredentialResolver credentialResolver,
            QueryAuditService auditService,
            RetrieveService retrieveService,
            AnswerService answerService
    ) {
        this.credentialResolver = credentialResolver;
        this.auditService = auditService;
        this.retrieveService = retrieveService;
        this.answerService = answerService;
    }

    @Override
    public AuditedQueryResult<RetrieveResponseVO> retrieve(
            String requestId,
            String authorizationHeader,
            String idempotencyKey,
            String callerTraceId,
            String actorRef,
            long knowledgeBaseId,
            RetrieveRequestDTO request
    ) {
        return execute(
                requestId,
                authorizationHeader,
                callerTraceId,
                actorRef,
                knowledgeBaseId,
                QueryAuditOperation.RETRIEVE,
                request == null ? null : request.getQuery(),
                request == null ? null : request.getMode(),
                telemetry -> retrieveService.retrieve(
                        authorizationHeader,
                        idempotencyKey,
                        knowledgeBaseId,
                        request,
                        telemetry
                )
        );
    }

    @Override
    public AuditedQueryResult<AnswerResponseVO> answer(
            String requestId,
            String authorizationHeader,
            String idempotencyKey,
            String callerTraceId,
            String actorRef,
            long knowledgeBaseId,
            AnswerRequestDTO request
    ) {
        return execute(
                requestId,
                authorizationHeader,
                callerTraceId,
                actorRef,
                knowledgeBaseId,
                QueryAuditOperation.ANSWER,
                request == null ? null : request.getQuery(),
                request == null ? null : request.getMode(),
                telemetry -> answerService.answer(
                        authorizationHeader,
                        idempotencyKey,
                        knowledgeBaseId,
                        request,
                        telemetry
                )
        );
    }

    private <T> AuditedQueryResult<T> execute(
            String requestId,
            String authorizationHeader,
            String callerTraceId,
            String actorRef,
            long knowledgeBaseId,
            QueryAuditOperation operation,
            String query,
            String requestedMode,
            Function<QueryExecutionTelemetry, T> execution
    ) {
        String credential = extractBearerCredential(authorizationHeader);
        ApplicationCredentialPrincipal principal;
        try {
            principal = credentialResolver.resolve(credential);
        } catch (ApplicationCredentialAuthenticationException exception) {
            throw invalidCredential();
        }
        String trace = null;
        String actor = null;
        RuntimeException auditHeaderFailure = null;
        try {
            trace = auditHeader(callerTraceId, operation);
        } catch (RuntimeException exception) {
            auditHeaderFailure = exception;
        }
        try {
            actor = auditHeader(actorRef, operation);
        } catch (RuntimeException exception) {
            if (auditHeaderFailure == null) {
                auditHeaderFailure = exception;
            }
        }
        String normalizedQuery = query == null ? null : query.strip();
        ApplicationQueryAuditEntity audit = auditService.start(new QueryAuditStart(
                requestId,
                principal,
                sha256(credential.substring("dq_app_".length(), credential.indexOf('.'))),
                knowledgeBaseId,
                operation,
                trace,
                actor,
                normalizedQuery == null || normalizedQuery.isEmpty()
                        ? null : sha256(normalizedQuery),
                normalizedQuery == null
                        ? null : normalizedQuery.codePointCount(0, normalizedQuery.length()),
                normalizeMode(requestedMode)
        ));
        QueryExecutionTelemetry telemetry = new QueryExecutionTelemetry();
        try {
            if (auditHeaderFailure != null) {
                throw auditHeaderFailure;
            }
            T body = execution.apply(telemetry);
            auditService.succeed(audit, telemetry);
            return new AuditedQueryResult<>(requestId, body);
        } catch (RuntimeException exception) {
            try {
                auditService.fail(
                        audit,
                        telemetry,
                        QueryAuditFailureClassifier.classify(exception)
                );
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw exception;
        }
    }

    private String extractBearerCredential(String authorizationHeader) {
        if (authorizationHeader == null) {
            throw invalidCredential();
        }
        String normalized = authorizationHeader.trim();
        if (normalized.length() <= BEARER_PREFIX.length()
                || !normalized.substring(0, BEARER_PREFIX.length())
                .toLowerCase(Locale.ROOT).equals(BEARER_PREFIX)) {
            throw invalidCredential();
        }
        String credential = normalized.substring(BEARER_PREFIX.length());
        if (credential.isBlank() || credential.chars().anyMatch(Character::isWhitespace)) {
            throw invalidCredential();
        }
        return credential;
    }

    private String auditHeader(String value, QueryAuditOperation operation) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 128
                || normalized.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            if (operation == QueryAuditOperation.ANSWER) {
                throw new AnswerException(
                        AnswerException.Reason.INVALID_REQUEST,
                        "Answer audit header is invalid"
                );
            }
            throw new RetrieveException(
                    RetrieveException.Reason.INVALID_REQUEST,
                    "Retrieve audit header is invalid"
            );
        }
        return normalized;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "HYBRID";
        }
        String normalized = mode.strip().toUpperCase(Locale.ROOT);
        return normalized.length() <= 16 ? normalized : null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private QueryAccessException invalidCredential() {
        return new QueryAccessException(
                APPLICATION_CREDENTIAL_INVALID,
                "Application credential is invalid"
        );
    }
}
