CREATE TABLE document_search_projection (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Elasticsearch双投影验收单主键ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID，逻辑外键',
    document_id BIGINT NOT NULL COMMENT '所属逻辑文档ID，逻辑外键',
    document_version_id BIGINT NOT NULL COMMENT '所属不可变文档版本ID，唯一',
    canonical_artifact_id BIGINT NOT NULL COMMENT 'Evidence投影来源canonical清单ID',
    canonical_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'canonical JSONL对象SHA-256',
    retrieval_artifact_id BIGINT NOT NULL COMMENT 'Navigation投影来源retrieval清单ID',
    retrieval_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'retrieval JSONL对象SHA-256',
    cluster_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完成校验时Elasticsearch Cluster UUID',
    evidence_index_name VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Evidence物理索引名',
    evidence_index_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Evidence物理索引UUID',
    evidence_mapping_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Evidence Mapping契约版本',
    evidence_expected_count INT UNSIGNED NOT NULL COMMENT 'canonical声明的EvidenceBlock数量',
    evidence_actual_count INT UNSIGNED NOT NULL COMMENT '校验时ES Evidence记录数量',
    navigation_index_name VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Navigation物理索引名',
    navigation_index_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Navigation物理索引UUID',
    navigation_mapping_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Navigation Mapping契约版本',
    navigation_expected_count INT UNSIGNED NOT NULL COMMENT 'retrieval声明的Profile加Node数量',
    navigation_actual_count INT UNSIGNED NOT NULL COMMENT '校验时ES Navigation记录数量',
    projection_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '输入artifact与投影契约SHA-256指纹',
    completed_at DATETIME(6) NOT NULL COMMENT '两个ES投影完成并通过校验的时间，UTC',
    created_at DATETIME(6) NOT NULL COMMENT '验收单首次创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '验收单最近受控重建更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_search_projection_version (document_version_id),
    KEY idx_document_search_projection_tenant_kb_version (
        tenant_id, knowledge_base_id, document_version_id
    ),
    KEY idx_document_search_projection_canonical (canonical_artifact_id),
    KEY idx_document_search_projection_retrieval (retrieval_artifact_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
