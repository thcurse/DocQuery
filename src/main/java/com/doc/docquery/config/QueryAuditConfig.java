package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册应用查询审计配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryAuditProperties.class)
public class QueryAuditConfig {
}
