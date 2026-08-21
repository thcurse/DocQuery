package com.doc.docquery.audit;

import com.doc.docquery.cache.QueryIdempotencyException;
import com.doc.docquery.exception.BusinessException;
import com.doc.docquery.security.QueryAccessException;
import com.doc.docquery.service.AnswerException;
import com.doc.docquery.service.RetrieveException;

/** 将既有服务异常映射为不泄露正文或依赖细节的审计终态。 */
public final class QueryAuditFailureClassifier {

    private QueryAuditFailureClassifier() {
    }

    public static QueryAuditFailure classify(Throwable failure) {
        if (failure instanceof QueryAccessException exception) {
            return switch (exception.reason()) {
                case APPLICATION_CREDENTIAL_INVALID -> rejected(
                        401, "AUTHENTICATION", "APPLICATION_CREDENTIAL_INVALID");
                case KNOWLEDGE_BASE_NOT_AVAILABLE -> rejected(
                        404, "AUTHORIZATION", "KNOWLEDGE_BASE_NOT_AVAILABLE");
            };
        }
        if (failure instanceof QueryIdempotencyException exception) {
            return switch (exception.reason()) {
                case INVALID_REQUEST -> rejected(400, "VALIDATION", "INVALID_QUERY_REQUEST");
                case IDEMPOTENCY_CONFLICT -> rejected(
                        409, "IDEMPOTENCY", "IDEMPOTENCY_CONFLICT");
                case CONTEXT_CHANGED -> rejected(
                        409, "IDEMPOTENCY", "IDEMPOTENCY_CONTEXT_CHANGED");
                case UNAVAILABLE, OWNERSHIP_LOST, RESULT_TOO_LARGE, CORRUPTED_STATE -> failed(
                        "IDEMPOTENCY", "QUERY_IDEMPOTENCY_UNAVAILABLE");
            };
        }
        if (failure instanceof RetrieveException exception) {
            return switch (exception.reason()) {
                case INVALID_REQUEST -> rejected(
                        400, "VALIDATION", "INVALID_RETRIEVE_REQUEST");
                case REQUEST_IN_PROGRESS -> rejected(
                        409, "IDEMPOTENCY", "REQUEST_IN_PROGRESS");
                case QUERY_EMBEDDING_UNAVAILABLE -> failed(
                        "QUERY_EMBEDDING", "QUERY_EMBEDDING_UNAVAILABLE");
                case SEARCH_UNAVAILABLE -> failed("SEARCH", "SEARCH_UNAVAILABLE");
                case EVIDENCE_UNAVAILABLE -> failed("EVIDENCE", "EVIDENCE_UNAVAILABLE");
            };
        }
        if (failure instanceof AnswerException exception) {
            return switch (exception.reason()) {
                case INVALID_REQUEST -> rejected(400, "VALIDATION", "INVALID_ANSWER_REQUEST");
                case REQUEST_IN_PROGRESS -> rejected(
                        409, "IDEMPOTENCY", "REQUEST_IN_PROGRESS");
                case MODEL_UNAVAILABLE -> failed("ANSWER_MODEL", "ANSWER_MODEL_UNAVAILABLE");
                case OUTPUT_INVALID -> failed("ANSWER_POLICY", "ANSWER_OUTPUT_INVALID");
                case EXECUTION_LIMIT_EXCEEDED -> failed(
                        "ANSWER_POLICY", "ANSWER_EXECUTION_LIMIT_EXCEEDED");
                case EXECUTION_TIMEOUT -> failed(
                        "TIMEOUT", "ANSWER_EXECUTION_TIMEOUT");
            };
        }
        if (failure instanceof BusinessException exception) {
            int status = switch (exception.failure()) {
                case VALIDATION -> 400;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                case FORBIDDEN -> 403;
                case TOO_LARGE -> 413;
                case UNSUPPORTED_MEDIA_TYPE -> 415;
                case UNAVAILABLE -> 503;
            };
            QueryAuditOutcome outcome = status < 500
                    ? QueryAuditOutcome.REJECTED : QueryAuditOutcome.FAILED;
            String category = "QUERY_AUDIT_UNAVAILABLE".equals(exception.code())
                    ? "AUDIT_STORAGE" : status < 500 ? "VALIDATION" : "DEPENDENCY";
            return new QueryAuditFailure(outcome, status, category, exception.code());
        }
        return new QueryAuditFailure(
                QueryAuditOutcome.FAILED,
                500,
                "INTERNAL",
                "INTERNAL_ERROR"
        );
    }

    private static QueryAuditFailure rejected(int status, String category, String code) {
        return new QueryAuditFailure(QueryAuditOutcome.REJECTED, status, category, code);
    }

    private static QueryAuditFailure failed(String category, String code) {
        return new QueryAuditFailure(QueryAuditOutcome.FAILED, 503, category, code);
    }
}
