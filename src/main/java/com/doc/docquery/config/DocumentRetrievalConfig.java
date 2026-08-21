package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 N2.4 配置；真实供应商适配器由单独配置按开关创建。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentRetrievalProperties.class)
public class DocumentRetrievalConfig {
}
