package com.doc.docquery.job;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.mapper.DocumentRetrievalArtifactMapper;
import com.doc.docquery.service.RetrievalArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 宽限期后仅清理 retrieval/ 下经数据库确认无引用的精确对象。 */
@Component
@ConditionalOnBean(RetrievalArtifactStore.class)
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrphanRetrievalArtifactReaper {

    private static final Logger LOG = LoggerFactory.getLogger(
            OrphanRetrievalArtifactReaper.class
    );

    private final RetrievalArtifactStore objectStore;
    private final DocumentRetrievalArtifactMapper artifactMapper;
    private final DocumentRetrievalProperties properties;

    public OrphanRetrievalArtifactReaper(
            RetrievalArtifactStore objectStore,
            DocumentRetrievalArtifactMapper artifactMapper,
            DocumentRetrievalProperties properties
    ) {
        this.objectStore = objectStore;
        this.artifactMapper = artifactMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${docquery.retrieval.orphan-scan-delay:15m}")
    public void runOnce() {
        Instant cutoff = Instant.now().minus(properties.getOrphanGrace());
        int removed = 0;
        for (RetrievalArtifactStore.ObjectSummary object : objectStore.list(
                properties.getRetrievalPrefix(),
                properties.getOrphanBatchSize()
        )) {
            if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) {
                continue;
            }
            try {
                long references = artifactMapper.countByRetrievalObject(
                        objectStore.bucketName(),
                        object.objectKey()
                );
                if (references == 0) {
                    objectStore.delete(object.objectKey());
                    removed++;
                }
            } catch (RuntimeException exception) {
                // 数据库状态不明时保留对象，避免回收任务扩大故障。
                LOG.warn("Orphan retrieval object check failed; object was retained");
            }
        }
        if (removed > 0) {
            LOG.info("Removed {} unreferenced retrieval objects", removed);
        }
    }
}
