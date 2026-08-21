ALTER TABLE processing_job
    ADD COLUMN lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '当前Consumer租约持有者，可为空' AFTER status,
    ADD COLUMN lease_until DATETIME(6) NULL
        COMMENT 'Consumer租约截止时间，UTC，可为空' AFTER lease_owner,
    ADD KEY idx_processing_job_status_lease_id (status, lease_until, id);

ALTER TABLE document_version
    MODIFY COLUMN source_bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '原文件所在S3兼容对象存储Bucket';
