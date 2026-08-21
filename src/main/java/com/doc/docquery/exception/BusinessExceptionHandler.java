package com.doc.docquery.exception;

import com.doc.docquery.cache.QueryIdempotencyException;
import com.doc.docquery.security.QueryAccessException;
import com.doc.docquery.service.AnswerException;
import com.doc.docquery.service.RetrieveException;
import com.doc.docquery.vo.ErrorVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

/** 将业务异常统一转换为稳定的错误码和 HTTP 状态。 */
@RestControllerAdvice
public class BusinessExceptionHandler {

    @ExceptionHandler(QueryAccessException.class)
    public ResponseEntity<ErrorVO> handleQueryAccess(QueryAccessException exception) {
        return switch (exception.reason()) {
            case APPLICATION_CREDENTIAL_INVALID -> ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorVO(
                            "APPLICATION_CREDENTIAL_INVALID",
                            "Application credential is invalid"
                    ));
            case KNOWLEDGE_BASE_NOT_AVAILABLE -> ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(new ErrorVO(
                            "KNOWLEDGE_BASE_NOT_AVAILABLE",
                            "KnowledgeBase is not available to this application"
                    ));
        };
    }

    @ExceptionHandler(QueryIdempotencyException.class)
    public ResponseEntity<ErrorVO> handleQueryIdempotency(
            QueryIdempotencyException exception,
            HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case INVALID_REQUEST -> serviceError(
                    HttpStatus.BAD_REQUEST,
                    invalidRequestCode(request),
                    invalidRequestMessage(request)
            );
            case IDEMPOTENCY_CONFLICT -> serviceError(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key is already bound to another request"
            );
            case CONTEXT_CHANGED -> serviceError(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONTEXT_CHANGED",
                    "Idempotency-Key is bound to another active-version snapshot"
            );
            case UNAVAILABLE, OWNERSHIP_LOST, RESULT_TOO_LARGE, CORRUPTED_STATE ->
                    serviceError(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "QUERY_IDEMPOTENCY_UNAVAILABLE",
                            "Query idempotency is unavailable"
                    );
        };
    }

    @ExceptionHandler(AnswerException.class)
    public ResponseEntity<ErrorVO> handleAnswer(AnswerException exception) {
        return switch (exception.reason()) {
            case INVALID_REQUEST -> serviceError(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ANSWER_REQUEST",
                    "Answer request is invalid"
            );
            case REQUEST_IN_PROGRESS -> serviceError(
                    HttpStatus.CONFLICT,
                    "REQUEST_IN_PROGRESS",
                    "Answer request is in progress"
            );
            case MODEL_UNAVAILABLE -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ANSWER_MODEL_UNAVAILABLE",
                    "Answer model is unavailable"
            );
            case OUTPUT_INVALID -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ANSWER_OUTPUT_INVALID",
                    "Answer model output is invalid"
            );
            case EXECUTION_LIMIT_EXCEEDED -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ANSWER_EXECUTION_LIMIT_EXCEEDED",
                    "Answer execution limit was exceeded"
            );
            case EXECUTION_TIMEOUT -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ANSWER_EXECUTION_TIMEOUT",
                    "Answer execution timed out"
            );
        };
    }

    @ExceptionHandler(RetrieveException.class)
    public ResponseEntity<ErrorVO> handleRetrieve(RetrieveException exception) {
        return switch (exception.reason()) {
            case INVALID_REQUEST -> serviceError(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_RETRIEVE_REQUEST",
                    "Retrieve request is invalid"
            );
            case REQUEST_IN_PROGRESS -> serviceError(
                    HttpStatus.CONFLICT,
                    "REQUEST_IN_PROGRESS",
                    "Retrieve request is in progress"
            );
            case QUERY_EMBEDDING_UNAVAILABLE -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "QUERY_EMBEDDING_UNAVAILABLE",
                    "Query embedding is unavailable"
            );
            case SEARCH_UNAVAILABLE -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SEARCH_UNAVAILABLE",
                    "Search is unavailable"
            );
            case EVIDENCE_UNAVAILABLE -> serviceError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EVIDENCE_UNAVAILABLE",
                    "Canonical evidence is unavailable"
            );
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorVO> handleUnreadableJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return serviceError(
                HttpStatus.BAD_REQUEST,
                invalidRequestCode(request),
                invalidRequestMessage(request)
        );
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorVO> handle(BusinessException exception) {
        HttpStatus status = switch (exception.failure()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(
                new ErrorVO(exception.code(), exception.getMessage())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorVO> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(
                new ErrorVO("VALIDATION_FAILED", message)
        );
    }

    /** Servlet 在进入 Controller 前拒绝超限 multipart 时仍返回稳定业务错误。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorVO> handleUploadLimit(
            MaxUploadSizeExceededException exception
    ) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                new ErrorVO("FILE_TOO_LARGE", "Uploaded file exceeds size limit")
        );
    }

    /** 缺少 metadata 或 file Part 时不要暴露框架内部异常格式。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorVO> handleMissingUploadPart(
            MissingServletRequestPartException exception
    ) {
        return ResponseEntity.badRequest().body(
                new ErrorVO("INVALID_UPLOAD_FILE", "Required upload part is missing")
        );
    }

    private ResponseEntity<ErrorVO> serviceError(
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status).body(new ErrorVO(code, message));
    }

    private String invalidRequestCode(HttpServletRequest request) {
        return isAnswer(request) ? "INVALID_ANSWER_REQUEST" : "INVALID_RETRIEVE_REQUEST";
    }

    private String invalidRequestMessage(HttpServletRequest request) {
        return isAnswer(request) ? "Answer request is invalid" : "Retrieve request is invalid";
    }

    private boolean isAnswer(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null
                && request.getRequestURI().endsWith("/answer");
    }
}
