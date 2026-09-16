package com.doc.docquery.job;

import com.doc.docquery.config.DocumentParsingProperties;
import com.doc.docquery.mapper.DocumentCanonicalArtifactMapper;
import com.doc.docquery.service.CanonicalArtifactStore;
import com.doc.docquery.service.ObjectListingPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 延迟清理写对象成功但清单未提交所留下的无引用 canonical 对象。 */
@Component
@ConditionalOnBean(CanonicalArtifactStore.class)
@ConditionalOnProperty(
        prefix = "docquery.object-storage",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrphanCanonicalArtifactReaper {

    private static final Logger LOG = LoggerFactory.getLogger(
            OrphanCanonicalArtifactReaper.class
    );

    private final CanonicalArtifactStore objectStore;
    /** 每次调度只处理一页，扫描结束后从头检查先前保留的对象。 */
    private String continuationToken;
    private final DocumentCanonicalArtifactMapper artifactMapper;
    private final DocumentParsingProperties properties;

    public OrphanCanonicalArtifactReaper(
            CanonicalArtifactStore objectStore,
            DocumentCanonicalArtifactMapper artifactMapper,
            DocumentParsingProperties properties
    ) {
        this.objectStore = objectStore;
        this.artifactMapper = artifactMapper;
        this.properties = properties;
    }

    /** 引用或数据库状态不确定时 fail closed，绝不按宽前缀盲删。 */
    @Scheduled(fixedDelayString = "${docquery.parsing.orphan-scan-delay:15m}")
    public synchronized void runOnce() {
        Instant cutoff = Instant.now().minus(properties.getOrphanGrace());
        int removed = 0;
        ObjectListingPage<CanonicalArtifactStore.ObjectSummary> page = objectStore.listPage(
                properties.getCanonicalPrefix(),
                properties.getOrphanBatchSize(),
                continuationToken
        );
        for (CanonicalArtifactStore.ObjectSummary object : page.objects()) {
            if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) {
                continue;
            }
            try {
                long references = artifactMapper.countByCanonicalObject(
                        objectStore.bucketName(),
                        object.objectKey()
                );
                if (references == 0) {
                    objectStore.delete(object.objectKey());
                    removed++;
                }
            } catch (RuntimeException exception) {
                LOG.warn("Orphan canonical object check failed; object was retained");
            }
        }
        continuationToken = page.nextContinuationToken();
        if (removed > 0) {
            LOG.info("Removed {} unreferenced canonical objects", removed);
        }
    }
}
