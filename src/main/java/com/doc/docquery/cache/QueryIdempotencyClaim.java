package com.doc.docquery.cache;

import lombok.Getter;

/**
 * Redis 查询幂等竞争结果及后续所有权凭据。
 *
 * <p>Storage Key 和 owner token 只供内部完成、续租和释放使用，不得写入 HTTP
 * 响应或日志。</p>
 */
@Getter
public final class QueryIdempotencyClaim {

    private final Status status;
    private final String storageKey;
    private final String ownerToken;
    private final String requestFingerprint;
    private final String snapshotFingerprint;
    private final String replayResult;

    private QueryIdempotencyClaim(
            Status status,
            String storageKey,
            String ownerToken,
            String requestFingerprint,
            String snapshotFingerprint,
            String replayResult
    ) {
        this.status = status;
        this.storageKey = storageKey;
        this.ownerToken = ownerToken;
        this.requestFingerprint = requestFingerprint;
        this.snapshotFingerprint = snapshotFingerprint;
        this.replayResult = replayResult;
    }

    public static QueryIdempotencyClaim owner(
            String storageKey,
            String ownerToken,
            String requestFingerprint,
            String snapshotFingerprint
    ) {
        return new QueryIdempotencyClaim(
                Status.OWNER,
                storageKey,
                ownerToken,
                requestFingerprint,
                snapshotFingerprint,
                null
        );
    }

    public static QueryIdempotencyClaim inProgress(
            String storageKey,
            String requestFingerprint,
            String snapshotFingerprint
    ) {
        return new QueryIdempotencyClaim(
                Status.IN_PROGRESS,
                storageKey,
                null,
                requestFingerprint,
                snapshotFingerprint,
                null
        );
    }

    public static QueryIdempotencyClaim replay(
            String storageKey,
            String requestFingerprint,
            String snapshotFingerprint,
            String replayResult
    ) {
        return new QueryIdempotencyClaim(
                Status.REPLAY,
                storageKey,
                null,
                requestFingerprint,
                snapshotFingerprint,
                replayResult
        );
    }

    /** 幂等竞争只产生所有者、执行中重复或成功重放三种非错误结果。 */
    public enum Status {
        OWNER,
        IN_PROGRESS,
        REPLAY
    }
}
