package com.doc.docquery.audit;

import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.vo.AnswerResponseVO;
import com.doc.docquery.vo.RetrieveResponseVO;
import lombok.Getter;

/** 单次调用线程内积累的安全计数；不保存问题、回答或Evidence正文。 */
@Getter
public final class QueryExecutionTelemetry {
    private String idempotencyDisposition;
    private String queryExecutionId;
    private String snapshotFingerprint;
    private Integer activeVersionCount;
    private String requestedMode;
    private String executedMode;
    private boolean degraded;
    private String degradationReason;
    private Integer resultCount;
    private Integer evidenceCount;
    private String answerStatus;
    private Integer citationCount;
    private int toolRounds;
    private int toolCalls;
    private int modelCalls;
    private int canonicalCharacters;

    public void snapshot(QueryAccessContext context) {
        if (context != null) {
            snapshotFingerprint = context.getSnapshotFingerprint();
            activeVersionCount = context.getActiveVersions().size();
        }
    }

    public void idempotency(QueryIdempotencyDisposition disposition) {
        idempotencyDisposition = disposition == null ? null : disposition.code();
    }

    public void capture(RetrieveResponseVO response) {
        if (response == null) {
            return;
        }
        queryExecutionId = response.getQueryExecutionId();
        requestedMode = response.getRequestedMode();
        executedMode = response.getExecutedMode();
        degraded = response.isDegraded();
        degradationReason = response.getDegradationReason();
        resultCount = response.getResults() == null ? 0 : response.getResults().size();
        evidenceCount = response.getResults() == null ? 0 : response.getResults().stream()
                .mapToInt(result -> result.getEvidence() == null
                        ? 0 : result.getEvidence().size())
                .sum();
    }

    public void capture(AnswerResponseVO response) {
        if (response == null) {
            return;
        }
        queryExecutionId = response.getQueryExecutionId();
        requestedMode = response.getRequestedMode();
        executedMode = response.getExecutedMode();
        degraded = response.isDegraded();
        degradationReason = response.getDegradationReason();
        answerStatus = response.getStatus();
        citationCount = response.getCitations() == null ? 0 : response.getCitations().size();
    }

    public void modelCall() {
        modelCalls++;
    }

    public void toolRound(int calls) {
        toolRounds++;
        toolCalls += calls;
    }

    public void canonicalCharacters(int characters) {
        canonicalCharacters = Math.max(characters, 0);
    }
}
