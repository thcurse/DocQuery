package com.doc.docquery.service;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.security.QueryAccessContext;

/** 为 Retrieve/Answer 提供 Redis 执行权、续租、成功重放和失败释放。 */
public interface QueryIdempotencyService {

    /** 原子竞争当前 Application 和 activeVersion 快照下的查询执行权。 */
    QueryIdempotencyClaim claim(
            QueryAccessContext accessContext,
            QueryOperation operation,
            String idempotencyKey,
            String requestFingerprint
    );

    /** 只有当前 owner 才能延长 RUNNING 租约。 */
    boolean renew(QueryIdempotencyClaim claim);

    /** 只有当前 owner 才能把成功响应写入短期重放缓存。 */
    void complete(QueryIdempotencyClaim claim, String responseJson);

    /** 业务失败时只释放当前 owner 的 RUNNING 状态，不缓存失败响应。 */
    void release(QueryIdempotencyClaim claim);
}
