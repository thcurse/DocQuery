CREATE TABLE document_canonical_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准化文档对象清单主键ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    document_version_id BIGINT NOT NULL COMMENT '所属文档版本ID，逻辑外键，第一版唯一',
    schema_version INT UNSIGNED NOT NULL COMMENT 'canonical JSONL结构版本，第一版为1',
    source_format VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原文件格式代码：1=PDF，2=DOCX，3=TXT，4=Markdown',
    parser_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DocQuery标准化解析器名称',
    parser_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DocQuery解析规则版本',
    canonical_bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '标准化对象所在S3兼容对象存储Bucket',
    canonical_object_key VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变canonical JSONL对象Key',
    canonical_size_bytes BIGINT NOT NULL COMMENT 'canonical JSONL对象实际字节数',
    canonical_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'canonical JSONL对象SHA-256',
    canonical_text_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '按稳定规则重建的标准化全文SHA-256',
    text_length BIGINT NOT NULL COMMENT '标准化全文UTF-16 code unit数量',
    block_count INT UNSIGNED NOT NULL COMMENT '正文证据块数量',
    heading_count INT UNSIGNED NOT NULL COMMENT '包含根节点的标题节点数量',
    warning_count INT UNSIGNED NOT NULL COMMENT '非致命解析警告数量',
    page_count INT UNSIGNED NULL COMMENT 'PDF物理页数，其他格式为空',
    created_at DATETIME(6) NOT NULL COMMENT '标准化对象清单创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_canonical_artifact_version (document_version_id),
    UNIQUE KEY uk_document_canonical_artifact_object (
        canonical_bucket, canonical_object_key
    ),
    KEY idx_document_canonical_artifact_tenant_version (
        tenant_id, document_version_id
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
