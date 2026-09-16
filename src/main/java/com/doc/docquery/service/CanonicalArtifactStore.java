package com.doc.docquery.service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** canonical JSONL 派生对象的供应商无关存储端口。 */
public interface CanonicalArtifactStore {

    /** 返回派生对象所在私有 Bucket。 */
    String bucketName();

    /** 流式写入不可变对象，并返回实际字节数与 SHA-256。 */
    WriteResult put(String objectKey, InputStream input, long sizeBytes, long maxBytes);

    /** 打开精确对象；调用方负责关闭。 */
    InputStream open(String objectKey);

    /** 判断精确对象是否存在。 */
    boolean exists(String objectKey);

    /** 删除精确对象，不存在时视为幂等成功。 */
    void delete(String objectKey);

    /** 按安全前缀列出首页有限数量对象。 */
    default List<ObjectSummary> list(String prefix, int limit) {
        return listPage(prefix, limit, null).objects();
    }

    /** 读取一页；首轮令牌传 null，后续原样传回上一页令牌。 */
    ObjectListingPage<ObjectSummary> listPage(String prefix, int limit, String continuationToken);

    /** 一次完整派生对象写入的可信结果。 */
    record WriteResult(long sizeBytes, String sha256) {
    }

    /** 回收扫描需要的最小对象元数据。 */
    record ObjectSummary(String objectKey, Instant lastModified) {
    }
}
