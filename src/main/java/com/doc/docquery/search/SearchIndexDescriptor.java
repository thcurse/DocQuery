package com.doc.docquery.search;

/** 两个物理索引在当前 Elasticsearch 集群中的稳定身份。 */
public record SearchIndexDescriptor(
        String clusterUuid,
        IndexRef evidence,
        IndexRef navigation
) {

    /** 物理索引名称、UUID 及业务 Mapping 版本。 */
    public record IndexRef(String name, String uuid, String mappingVersion) {
    }
}
