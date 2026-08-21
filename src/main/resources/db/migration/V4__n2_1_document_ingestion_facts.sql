CREATE TABLE document (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '逻辑文档主键ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID，逻辑外键',
    name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '知识库内大小写敏感唯一的逻辑文档名称',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文档状态代码：1=正常，2=删除中，3=已删除',
    active_version_id BIGINT NULL COMMENT '当前参与检索的文档版本ID，逻辑外键，可为空',
    latest_version_id BIGINT NULL COMMENT '最新受理的文档版本ID，逻辑外键，创建事务提交后非空',
    created_by_admin_id BIGINT NOT NULL COMMENT '创建文档的租户管理员ID，逻辑外键',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_tenant_knowledge_base_name (
        tenant_id, knowledge_base_id, name
    ),
    KEY idx_document_tenant_knowledge_base_status_id (
        tenant_id, knowledge_base_id, status, id
    ),
    KEY idx_document_active_version_id (active_version_id),
    KEY idx_document_latest_version_id (latest_version_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE document_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档版本主键ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    document_id BIGINT NOT NULL COMMENT '所属逻辑文档ID，逻辑外键',
    version_no INT UNSIGNED NOT NULL COMMENT '文档内从1开始递增的版本号',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '版本状态代码：1=处理中，2=可检索，3=处理失败',
    source_format VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原文件格式代码：1=PDF，2=DOCX，3=TXT，4=Markdown',
    original_filename VARCHAR(512) NOT NULL COMMENT '上传时的原始文件名，不作为文档身份',
    source_bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原文件所在MinIO Bucket',
    source_object_key VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原文件持久对象Key，不保存预签名URL',
    source_size_bytes BIGINT NOT NULL COMMENT '原文件字节数，必须大于0',
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原文件内容SHA-256，小写64位十六进制',
    source_content_type VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原文件Content-Type元数据，不单独决定格式',
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '管理面幂等键SHA-256，不保存原始键',
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化上传请求SHA-256指纹',
    accepted_by_admin_id BIGINT NOT NULL COMMENT '受理上传的租户管理员ID，逻辑外键',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定失败分类，可为空',
    failure_message VARCHAR(1000) NULL COMMENT '面向管理员的脱敏失败摘要，可为空',
    ready_at DATETIME(6) NULL COMMENT '进入可检索状态的时间，UTC，可为空',
    failed_at DATETIME(6) NULL COMMENT '进入失败状态的时间，UTC，可为空',
    created_at DATETIME(6) NOT NULL COMMENT '上传受理时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_version_document_version_no (document_id, version_no),
    UNIQUE KEY uk_document_version_tenant_idempotency (
        tenant_id, idempotency_key_hash
    ),
    UNIQUE KEY uk_document_version_source_object (
        source_bucket, source_object_key
    ),
    KEY idx_document_version_tenant_document_status_version (
        tenant_id, document_id, status, version_no
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE processing_job (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档处理任务主键ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    document_version_id BIGINT NOT NULL COMMENT '目标文档版本ID，逻辑外键',
    job_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务类型代码：1=文档入库',
    attempt_no INT UNSIGNED NOT NULL COMMENT '文档版本内从1开始的处理尝试号',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务状态代码：1=待处理，2=处理中，3=成功，4=失败',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定失败分类，可为空',
    failure_message VARCHAR(1000) NULL COMMENT '面向管理员的脱敏失败摘要，可为空',
    started_at DATETIME(6) NULL COMMENT '任务开始时间，UTC，可为空',
    finished_at DATETIME(6) NULL COMMENT '任务结束时间，UTC，可为空',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_processing_job_version_attempt (
        document_version_id, attempt_no
    ),
    KEY idx_processing_job_tenant_status_updated_id (
        tenant_id, status, updated_at, id
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Outbox事件主键ID，同时作为稳定eventId',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    document_version_id BIGINT NOT NULL COMMENT '目标文档版本ID，逻辑外键',
    processing_job_id BIGINT NOT NULL COMMENT '目标处理任务ID，逻辑外键',
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件类型代码：1=请求处理文档版本',
    payload JSON NOT NULL COMMENT '只包含稳定资源ID的事件负载',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发布状态代码：1=待发布，2=发布中，3=已发送',
    attempt_count INT UNSIGNED NOT NULL COMMENT '发布尝试次数，初始为0',
    available_at DATETIME(6) NOT NULL COMMENT '下一次允许发布的时间，UTC',
    locked_by VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Publisher租约持有者，可为空',
    locked_until DATETIME(6) NULL COMMENT 'Publisher租约到期时间，UTC，可为空',
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近发布失败分类，可为空',
    last_error_message VARCHAR(1000) NULL COMMENT '最近发布失败脱敏摘要，可为空',
    sent_at DATETIME(6) NULL COMMENT 'RabbitMQ Confirm成功时间，UTC，可为空',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_type_job (event_type, processing_job_id),
    KEY idx_outbox_event_status_available_lock_id (
        status, available_at, locked_until, id
    ),
    KEY idx_outbox_event_tenant_id (tenant_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
