ALTER TABLE document
    ADD COLUMN active_name VARCHAR(200) CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_as_cs NULL
        COMMENT '未删除文档占用的大小写敏感名称；DELETED后为空以允许名称复用'
        AFTER name,
    ADD COLUMN deletion_requested_at DATETIME(6) NULL
        COMMENT '进入删除中的UTC时间'
        AFTER latest_version_id,
    ADD COLUMN deleted_at DATETIME(6) NULL
        COMMENT '全部外部内容清理完成并进入已删除的UTC时间'
        AFTER deletion_requested_at;

UPDATE document
SET active_name = name
WHERE status <> '3';

ALTER TABLE document
    DROP INDEX uk_document_tenant_knowledge_base_name,
    ADD UNIQUE KEY uk_document_tenant_knowledge_base_active_name (
        tenant_id, knowledge_base_id, active_name
    );

ALTER TABLE document_version
    ADD COLUMN content_deleted_at DATETIME(6) NULL
        COMMENT '该版本原文件、派生对象和检索投影完成物理清理的UTC时间'
        AFTER failed_at;

CREATE TABLE document_deletion_job (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档删除任务尝试主键ID',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID，逻辑外键',
    document_id BIGINT NOT NULL COMMENT '待删除逻辑文档ID，逻辑外键',
    attempt_no INT UNSIGNED NOT NULL COMMENT '同一文档从1开始递增的删除尝试号',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '删除任务状态代码：1=待处理，2=处理中，3=成功，4=失败',
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '当前删除Consumer租约持有者',
    lease_until DATETIME(6) NULL COMMENT '删除Consumer租约到期时间UTC',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '稳定失败分类',
    failure_message VARCHAR(1000) NULL COMMENT '面向管理员的脱敏失败摘要',
    failure_retryable TINYINT(1) NULL
        COMMENT '最终失败是否允许管理员人工重试：0=否，1=是，非失败状态为空',
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '删除或删除重试Idempotency-Key的SHA-256',
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '删除命令的稳定SHA-256指纹',
    requested_by_admin_id BIGINT NOT NULL COMMENT '发起删除命令的租户管理员ID',
    started_at DATETIME(6) NULL COMMENT '开始执行时间UTC',
    finished_at DATETIME(6) NULL COMMENT '执行结束时间UTC',
    created_at DATETIME(6) NOT NULL COMMENT '删除尝试受理时间UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '删除尝试最后更新时间UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_deletion_job_document_attempt (
        document_id, attempt_no
    ),
    UNIQUE KEY uk_document_deletion_job_tenant_idempotency (
        tenant_id, idempotency_key_hash
    ),
    KEY idx_document_deletion_job_tenant_document_attempt (
        tenant_id, document_id, attempt_no, id
    ),
    KEY idx_document_deletion_job_status_lease_id (
        status, lease_until, id
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE outbox_event
    MODIFY COLUMN document_version_id BIGINT NULL
        COMMENT '入库事件目标文档版本ID；删除事件为空',
    MODIFY COLUMN processing_job_id BIGINT NULL
        COMMENT '入库事件目标处理任务ID；删除事件为空',
    ADD COLUMN document_id BIGINT NULL
        COMMENT '事件关联逻辑文档ID；N4.2前历史入库事件可为空'
        AFTER tenant_id,
    ADD COLUMN document_deletion_job_id BIGINT NULL
        COMMENT '删除事件目标删除任务ID；入库事件为空'
        AFTER processing_job_id,
    ADD UNIQUE KEY uk_outbox_event_type_deletion_job (
        event_type, document_deletion_job_id
    );
