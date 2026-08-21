package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** N3.4 应用查询审计的中断判定和数据保留边界。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.query.audit")
public class QueryAuditProperties {
    private Duration staleAfter = Duration.ofMinutes(15);
    private Duration retention = Duration.ofDays(90);
    private Duration maintenanceDelay = Duration.ofHours(1);
    private int maintenanceBatchSize = 500;
}
