package com.doc.docquery.job;

import com.doc.docquery.config.QueryAuditProperties;
import com.doc.docquery.mapper.ApplicationQueryAuditMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 有界标记中断审计并清理超过保留期的终态元数据。 */
@Component
public class QueryAuditMaintenanceJob {

    private final ApplicationQueryAuditMapper mapper;
    private final QueryAuditProperties properties;

    public QueryAuditMaintenanceJob(
            ApplicationQueryAuditMapper mapper,
            QueryAuditProperties properties
    ) {
        this.mapper = mapper;
        this.properties = properties;
        if (properties.getStaleAfter().isNegative() || properties.getStaleAfter().isZero()
                || properties.getRetention().compareTo(properties.getStaleAfter()) <= 0
                || properties.getMaintenanceBatchSize() < 1
                || properties.getMaintenanceBatchSize() > 10_000) {
            throw new IllegalArgumentException("Query audit maintenance properties are invalid");
        }
    }

    @Scheduled(fixedDelayString = "${docquery.query.audit.maintenance-delay:1h}")
    public void maintain() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        mapper.markInterruptedBefore(
                now.minus(properties.getStaleAfter()),
                now,
                properties.getMaintenanceBatchSize()
        );
        mapper.deleteTerminalBefore(
                now.minus(properties.getRetention()),
                properties.getMaintenanceBatchSize()
        );
    }
}
