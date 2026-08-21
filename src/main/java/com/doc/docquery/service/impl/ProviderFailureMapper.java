package com.doc.docquery.service.impl;

import com.doc.docquery.service.RetrievalGenerationException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

/** 把供应商/SDK 异常收敛为 N2.4 稳定错误码，且不复制响应正文。 */
final class ProviderFailureMapper {

    private ProviderFailureMapper() {
    }

    static RetrievalGenerationException chat(RuntimeException exception) {
        return map(
                exception,
                "RETRIEVAL_MODEL_AUTH_FAILED",
                "RETRIEVAL_MODEL_UNAVAILABLE",
                "Chat model request failed"
        );
    }

    static RetrievalGenerationException embedding(RuntimeException exception) {
        return map(
                exception,
                "EMBEDDING_MODEL_AUTH_FAILED",
                "EMBEDDING_MODEL_UNAVAILABLE",
                "Embedding model request failed"
        );
    }

    private static RetrievalGenerationException map(
            RuntimeException exception,
            String authCode,
            String unavailableCode,
            String safeMessage
    ) {
        if (exception instanceof AuthenticationException
                || exception instanceof InvalidRequestException
                || exception instanceof ModelNotFoundException
                || exception instanceof NonRetriableException) {
            return new RetrievalGenerationException(authCode, safeMessage, false);
        }
        if (exception instanceof HttpException http) {
            int status = http.statusCode();
            boolean retryable = status == 429 || status >= 500;
            return new RetrievalGenerationException(
                    retryable ? unavailableCode : authCode,
                    safeMessage,
                    retryable
            );
        }
        if (exception instanceof RetriableException) {
            return new RetrievalGenerationException(
                    unavailableCode,
                    safeMessage,
                    true
            );
        }
        return new RetrievalGenerationException(
                unavailableCode,
                safeMessage,
                true
        );
    }
}
