package com.doc.docquery.search;

import com.doc.docquery.config.SearchProjectionProperties;

import java.util.regex.Pattern;

/** 生成 N2.5 冻结的 strict Mapping；不依赖 IK 或其他 Elasticsearch 插件。 */
public final class SearchIndexMappings {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,254}");

    private SearchIndexMappings() {
    }

    public static String evidence(SearchProjectionProperties properties) {
        validate(properties);
        return """
                {
                  "settings":{"number_of_shards":%d,"number_of_replicas":%d},
                  "aliases":{"%s":{}},
                  "mappings":{
                    "dynamic":"strict",
                    "_meta":{"docquery_mapping_version":"%s"},
                    "properties":{
                      "tenant_id":{"type":"long"},
                      "knowledge_base_id":{"type":"long"},
                      "document_id":{"type":"long"},
                      "document_version_id":{"type":"long"},
                      "block_id":{"type":"keyword"},
                      "ordinal":{"type":"integer"},
                      "kind":{"type":"keyword"},
                      "document_title":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "heading_node_id":{"type":"keyword"},
                      "heading_path":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "text":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "canonical_start":{"type":"long"},
                      "canonical_end":{"type":"long"},
                      "continuation_group_id":{"type":"keyword"},
                      "source_type":{"type":"keyword"},
                      "page_number":{"type":"integer"},
                      "page_block_ordinal":{"type":"integer"},
                      "page_character_start":{"type":"integer"},
                      "page_character_end":{"type":"integer"},
                      "body_element_index":{"type":"integer"},
                      "table_row":{"type":"integer"},
                      "table_column":{"type":"integer"},
                      "cell_paragraph_index":{"type":"integer"},
                      "start_line":{"type":"integer"},
                      "start_column":{"type":"integer"},
                      "end_line":{"type":"integer"},
                      "end_column":{"type":"integer"},
                      "canonical_artifact_id":{"type":"long"},
                      "canonical_artifact_sha256":{"type":"keyword"},
                      "projection_fingerprint":{"type":"keyword"},
                      "mapping_version":{"type":"keyword"}
                    }
                  }
                }
                """.formatted(
                properties.getShards(),
                properties.getReplicas(),
                properties.evidenceAlias(),
                properties.getEvidenceMappingVersion()
        );
    }

    public static String navigation(SearchProjectionProperties properties) {
        validate(properties);
        return """
                {
                  "settings":{"number_of_shards":%d,"number_of_replicas":%d},
                  "aliases":{"%s":{}},
                  "mappings":{
                    "dynamic":"strict",
                    "_meta":{"docquery_mapping_version":"%s"},
                    "properties":{
                      "tenant_id":{"type":"long"},
                      "knowledge_base_id":{"type":"long"},
                      "document_id":{"type":"long"},
                      "document_version_id":{"type":"long"},
                      "card_id":{"type":"keyword"},
                      "card_type":{"type":"keyword"},
                      "document_title":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "heading_node_id":{"type":"keyword"},
                      "parent_heading_node_id":{"type":"keyword"},
                      "sibling_order":{"type":"integer"},
                      "depth":{"type":"integer"},
                      "title":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "title_path":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "section_start_block_ordinal":{"type":"integer"},
                      "section_end_block_ordinal_exclusive":{"type":"integer"},
                      "canonical_start":{"type":"long"},
                      "canonical_end":{"type":"long"},
                      "source_start":{"type":"object","dynamic":"strict","properties":%s},
                      "source_end":{"type":"object","dynamic":"strict","properties":%s},
                      "purpose":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "summary":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "topics":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "aliases":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "answerable_questions":{"type":"text","analyzer":"cjk","fields":{"standard":{"type":"text","analyzer":"standard"}}},
                      "embedding_provider":{"type":"keyword"},
                      "embedding_model":{"type":"keyword"},
                      "embedding_dimension":{"type":"integer"},
                      "embedding_template_version":{"type":"keyword"},
                      "embedding_input_sha256":{"type":"keyword"},
                      "embedding_value_sha256":{"type":"keyword"},
                      "embedding":{"type":"dense_vector","dims":%d,"index":true,"similarity":"cosine"},
                      "canonical_artifact_id":{"type":"long"},
                      "canonical_artifact_sha256":{"type":"keyword"},
                      "retrieval_artifact_id":{"type":"long"},
                      "retrieval_artifact_sha256":{"type":"keyword"},
                      "projection_fingerprint":{"type":"keyword"},
                      "mapping_version":{"type":"keyword"}
                    }
                  }
                }
                """.formatted(
                properties.getShards(),
                properties.getReplicas(),
                properties.navigationAlias(),
                properties.getNavigationMappingVersion(),
                sourcePositionProperties(),
                sourcePositionProperties(),
                properties.getEmbeddingDimension()
        );
    }

    private static String sourcePositionProperties() {
        return """
                {
                  "source_type":{"type":"keyword"},
                  "page_number":{"type":"integer"},
                  "page_block_ordinal":{"type":"integer"},
                  "page_character_start":{"type":"integer"},
                  "page_character_end":{"type":"integer"},
                  "body_element_index":{"type":"integer"},
                  "table_row":{"type":"integer"},
                  "table_column":{"type":"integer"},
                  "cell_paragraph_index":{"type":"integer"},
                  "start_line":{"type":"integer"},
                  "start_column":{"type":"integer"},
                  "end_line":{"type":"integer"},
                  "end_column":{"type":"integer"}
                }
                """;
    }

    private static void validate(SearchProjectionProperties properties) {
        if (!SAFE_NAME.matcher(properties.evidencePhysicalIndex()).matches()
                || !SAFE_NAME.matcher(properties.navigationPhysicalIndex()).matches()
                || !SAFE_NAME.matcher(properties.evidenceAlias()).matches()
                || !SAFE_NAME.matcher(properties.navigationAlias()).matches()
                || !SAFE_NAME.matcher(properties.getEvidenceMappingVersion()).matches()
                || !SAFE_NAME.matcher(properties.getNavigationMappingVersion()).matches()
                || properties.getEmbeddingDimension() < 1
                || properties.getBulkMaxActions() < 1
                || properties.getBulkMaxBytes() < 1
                || properties.getShards() < 1
                || properties.getReplicas() < 0) {
            throw new SearchProjectionException(
                    "SEARCH_INDEX_SCHEMA_MISMATCH",
                    "Search projection configuration is invalid",
                    false
            );
        }
    }
}
