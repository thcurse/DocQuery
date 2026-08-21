ALTER TABLE processing_job
    ADD COLUMN failure_retryable TINYINT(1) NULL
        COMMENT '最终失败是否允许管理员人工重试：0=否，1=是，非失败状态为空'
        AFTER failure_message,
    ADD COLUMN idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '人工重试Idempotency-Key的SHA-256，初始任务为空'
        AFTER failure_retryable,
    ADD COLUMN request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '人工重试命令的稳定SHA-256指纹，初始任务为空'
        AFTER idempotency_key_hash,
    ADD UNIQUE KEY uk_processing_job_tenant_idempotency (
        tenant_id, idempotency_key_hash
    ),
    ADD KEY idx_processing_job_tenant_updated_id (
        tenant_id, updated_at, id
    );

UPDATE processing_job
SET failure_retryable = 0
WHERE status = '4';

ALTER TABLE document
    ADD KEY idx_document_tenant_kb_updated_id (
        tenant_id, knowledge_base_id, updated_at, id
    );

ALTER TABLE document_version
    ADD KEY idx_document_version_tenant_document_version_id (
        tenant_id, document_id, version_no, id
    );
