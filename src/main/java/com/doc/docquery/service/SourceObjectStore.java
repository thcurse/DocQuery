package com.doc.docquery.service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * 不可变原文件的供应商无关存储端口。
 *
 * <p>调用方只能使用精确 Key 写入、读取和删除；Bucket 由部署配置决定，不能
 * 从 HTTP 请求中传入。</p>
 */
public interface SourceObjectStore {

    /** 返回当前适配器管理的私有 Bucket。 */
    String bucketName();

    /**
     * 流式写入一个新对象，并返回服务端实际读取到的字节数与 SHA-256。
     */
    WriteResult put(
            String objectKey,
            InputStream input,
            long declaredSize,
            long maxBytes,
            String contentType
    );

    /** 打开对象读取流；调用方负责关闭。 */
    InputStream open(String objectKey);

    /** 判断精确对象是否存在。 */
    boolean exists(String objectKey);

    /** 删除精确对象；不存在时视为幂等成功。 */
    void delete(String objectKey);

    /** 按安全前缀列出有限数量对象，供孤立对象回收器使用。 */
    List<ObjectSummary> list(String prefix, int limit);

    /** 一次完整上传的可信结果。 */
    record WriteResult(long sizeBytes, String sha256) {
    }

    /** 回收扫描所需的最小对象元数据。 */
    record ObjectSummary(String objectKey, Instant lastModified) {
    }
}
