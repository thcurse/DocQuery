package com.doc.docquery.job;

import com.doc.docquery.config.ObjectStorageProperties;
import com.doc.docquery.mapper.DocumentVersionMapper;
import com.doc.docquery.service.SourceObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 延迟清理上传协调失败留下的无引用原文件。
 *
 * <p>任何数据库读取异常、对象年龄不足或引用不确定都会保守保留对象。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrphanSourceObjectReaper {

    private static final Logger LOG = LoggerFactory.getLogger(
            OrphanSourceObjectReaper.class
    );

    private final SourceObjectStore objectStore;
    private final DocumentVersionMapper documentVersionMapper;
    private final ObjectStorageProperties properties;

    public OrphanSourceObjectReaper(
            SourceObjectStore objectStore,
            DocumentVersionMapper documentVersionMapper,
            ObjectStorageProperties properties
    ) {
        this.objectStore = objectStore;
        this.documentVersionMapper = documentVersionMapper;
        this.properties = properties;
    }

    /** 定时入口；public 的 runOnce 也允许集成测试直接验证单轮行为。 */
    @Scheduled(fixedDelayString = "${docquery.object-storage.orphan-scan-delay:15m}")
    public void runOnce() {
        Instant cutoff = Instant.now().minus(properties.getOrphanGrace());
        int removed = 0;
        for (SourceObjectStore.ObjectSummary object : objectStore.list(
                properties.getOrphanPrefix(),
                properties.getOrphanBatchSize()
        )) {
            if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) {
                continue;
            }
            try {
                long references = documentVersionMapper.countBySourceObject(
                        objectStore.bucketName(),
                        object.objectKey()
                );
                if (references == 0) {
                    objectStore.delete(object.objectKey());
                    removed++;
                }
            } catch (RuntimeException exception) {
                // 不输出对象 Key 或底层 SQL/SDK 信息；本轮对该对象 fail closed。
                LOG.warn("Orphan source object check failed; object was retained");
            }
        }
        if (removed > 0) {
            LOG.info("Removed {} unreferenced source objects", removed);
        }
    }
}
