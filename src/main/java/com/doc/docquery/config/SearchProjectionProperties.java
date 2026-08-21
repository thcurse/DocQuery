package com.doc.docquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * N2.5 Elasticsearch 投影配置。
 *
 * <p>Mapping 版本和向量维度属于业务契约，默认值会被投影指纹记录；Endpoint、
 * API Key 和批量大小只影响部署及传输，不改变业务身份。</p>
 */
@ConfigurationProperties(prefix = "docquery.search")
public class SearchProjectionProperties {

    private boolean enabled;
    private String endpoint = "http://localhost:19200";
    private String apiKey;
    private String indexPrefix = "docquery";
    private String evidenceMappingVersion = "evidence-v1";
    private String navigationMappingVersion = "navigation-v1";
    private int embeddingDimension = 2560;
    private int bulkMaxActions = 500;
    private long bulkMaxBytes = 5L * 1024 * 1024;
    private int shards = 1;
    private int replicas = 0;

    public String evidencePhysicalIndex() {
        return indexPrefix + "-" + evidenceMappingVersion;
    }

    public String evidenceAlias() {
        return indexPrefix + "-evidence";
    }

    public String navigationPhysicalIndex() {
        return indexPrefix + "-" + navigationMappingVersion;
    }

    public String navigationAlias() {
        return indexPrefix + "-navigation";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = indexPrefix;
    }

    public String getEvidenceMappingVersion() {
        return evidenceMappingVersion;
    }

    public void setEvidenceMappingVersion(String evidenceMappingVersion) {
        this.evidenceMappingVersion = evidenceMappingVersion;
    }

    public String getNavigationMappingVersion() {
        return navigationMappingVersion;
    }

    public void setNavigationMappingVersion(String navigationMappingVersion) {
        this.navigationMappingVersion = navigationMappingVersion;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public int getBulkMaxActions() {
        return bulkMaxActions;
    }

    public void setBulkMaxActions(int bulkMaxActions) {
        this.bulkMaxActions = bulkMaxActions;
    }

    public long getBulkMaxBytes() {
        return bulkMaxBytes;
    }

    public void setBulkMaxBytes(long bulkMaxBytes) {
        this.bulkMaxBytes = bulkMaxBytes;
    }

    public int getShards() {
        return shards;
    }

    public void setShards(int shards) {
        this.shards = shards;
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }
}
