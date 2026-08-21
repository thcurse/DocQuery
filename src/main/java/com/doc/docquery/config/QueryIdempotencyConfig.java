package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 N3 查询幂等配置；Redis 连接由 Spring Boot Data Redis 自动配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryIdempotencyProperties.class)
public class QueryIdempotencyConfig {
}
