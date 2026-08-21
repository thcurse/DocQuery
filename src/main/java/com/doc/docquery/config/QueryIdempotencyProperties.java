package com.doc.docquery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** N3 查询 Redis 幂等的开关、TTL 和单响应资源边界。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "docquery.query.idempotency")
public class QueryIdempotencyProperties {

    /** 默认关闭；正式服务 API 开放时必须显式启用并保证 Redis 可用。 */
    private boolean enabled;
    /** 不含 Tenant/Application/操作和 Key 摘要的固定版本前缀。 */
    private String keyPrefix = "docquery:query-idempotency:v1";
    /** 昂贵执行所有权租约；N3.3 需要在工具轮次间续租。 */
    private Duration runningTtl = Duration.ofMinutes(10);
    /** 成功响应允许重放的短 TTL。 */
    private Duration resultTtl = Duration.ofMinutes(5);
    /** 防止把无界原文或回答写入 Redis。 */
    private int maxResultBytes = 1024 * 1024;
}
