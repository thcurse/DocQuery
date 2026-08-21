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

    List<ObjectSummary> list(String prefix, int limit);

    record WriteResult(long sizeBytes, String sha256) {
    }

    record ObjectSummary(String objectKey, Instant lastModified) {
    }
}
