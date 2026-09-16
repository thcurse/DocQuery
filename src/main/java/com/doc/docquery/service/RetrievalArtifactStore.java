package com.doc.docquery.service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** retrieval JSONL 的供应商无关对象存储端口。 */
public interface RetrievalArtifactStore {

    String bucketName();

    WriteResult put(String objectKey, InputStream input, long sizeBytes, long maxBytes);

    InputStream open(String objectKey);

    boolean exists(String objectKey);

    void delete(String objectKey);

    /** 按安全前缀列出首页有限数量对象。 */
    default List<ObjectSummary> list(String prefix, int limit) {
        return listPage(prefix, limit, null).objects();
    }

    /** 读取一页；首轮令牌传 null，后续原样传回上一页令牌。 */
    ObjectListingPage<ObjectSummary> listPage(String prefix, int limit, String continuationToken);

    record WriteResult(long sizeBytes, String sha256) {
    }

    record ObjectSummary(String objectKey, Instant lastModified) {
    }
}
