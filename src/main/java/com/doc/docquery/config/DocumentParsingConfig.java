package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 N2.3 解析配置；Parser 本身不依赖外部对象存储是否启用。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentParsingProperties.class)
public class DocumentParsingConfig {
}
