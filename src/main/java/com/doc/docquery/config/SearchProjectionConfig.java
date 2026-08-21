package com.doc.docquery.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只在显式开启 N2.5 Search 投影时创建官方 Elasticsearch Java Client。 */
@Configuration
@EnableConfigurationProperties(SearchProjectionProperties.class)
public class SearchProjectionConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "docquery.search", name = "enabled", havingValue = "true")
    public ElasticsearchClient elasticsearchClient(SearchProjectionProperties properties) {
        return ElasticsearchClient.of(builder -> {
            builder.host(properties.getEndpoint());
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                builder.apiKey(properties.getApiKey());
            }
            return builder;
        });
    }
}
