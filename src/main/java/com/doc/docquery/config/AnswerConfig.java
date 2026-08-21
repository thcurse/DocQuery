package com.doc.docquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 N3.3 Answer 策略配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnswerProperties.class)
public class AnswerConfig {
}
