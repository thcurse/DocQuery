UPDATE tenant
SET status = CASE status
    WHEN 'ACTIVE' THEN '1'
    WHEN 'DISABLED' THEN '2'
    ELSE status
END;

UPDATE admin_user
SET role = CASE role
        WHEN 'PLATFORM_ADMIN' THEN '1'
        WHEN 'TENANT_ADMIN' THEN '2'
        ELSE role
    END,
    status = CASE status
        WHEN 'ACTIVE' THEN '1'
        WHEN 'DISABLED' THEN '2'
        ELSE status
    END;

UPDATE application
SET status = CASE status
    WHEN 'ACTIVE' THEN '1'
    WHEN 'DISABLED' THEN '2'
    ELSE status
END;

UPDATE knowledge_base
SET status = CASE status
    WHEN 'ACTIVE' THEN '1'
    WHEN 'DISABLED' THEN '2'
    ELSE status
END;

UPDATE credential
SET status = CASE status
    WHEN 'ACTIVE' THEN '1'
    WHEN 'REVOKED' THEN '3'
    ELSE status
END;

UPDATE application_grant
SET permission = CASE permission
        WHEN 'READ' THEN '1'
        WHEN 'WRITE' THEN '2'
        WHEN 'READ_WRITE' THEN '3'
        ELSE permission
    END,
    status = CASE status
        WHEN 'ACTIVE' THEN '1'
        WHEN 'REVOKED' THEN '3'
        ELSE status
    END;

ALTER TABLE tenant
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '租户主键ID',
    MODIFY COLUMN name VARCHAR(200) NOT NULL COMMENT '租户名称',
    MODIFY COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态代码：1=启用，2=停用',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC';

ALTER TABLE admin_user
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '管理员主键ID',
    MODIFY COLUMN tenant_id BIGINT NULL COMMENT '所属租户ID，平台管理员为空，逻辑外键',
    MODIFY COLUMN login_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '管理员登录名，全局唯一',
    MODIFY COLUMN password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '管理员密码哈希，不保存明文',
    MODIFY COLUMN role VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '角色代码：1=平台管理员，2=租户管理员',
    MODIFY COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态代码：1=启用，2=停用',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC';

ALTER TABLE application
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '应用主键ID',
    MODIFY COLUMN tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    MODIFY COLUMN code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户内唯一且不可修改的应用代码',
    MODIFY COLUMN name VARCHAR(200) NOT NULL COMMENT '应用展示名称',
    MODIFY COLUMN environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '运行环境：DEVELOPMENT=开发，TESTING=测试，PRODUCTION=生产',
    MODIFY COLUMN description VARCHAR(1000) NULL COMMENT '应用说明，可为空',
    MODIFY COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态代码：1=启用，2=停用',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC';

ALTER TABLE knowledge_base
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '知识库主键ID',
    MODIFY COLUMN tenant_id BIGINT NOT NULL COMMENT '所属租户ID，逻辑外键',
    MODIFY COLUMN name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '租户内大小写敏感唯一的知识库名称',
    MODIFY COLUMN description VARCHAR(1000) NULL COMMENT '知识库说明，可为空',
    MODIFY COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态代码：1=启用，2=停用',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '最后更新时间，UTC';

ALTER TABLE credential
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '应用凭证主键ID',
    MODIFY COLUMN application_id BIGINT NOT NULL COMMENT '所属应用ID，逻辑外键',
    MODIFY COLUMN name VARCHAR(200) NOT NULL COMMENT '凭证名称',
    MODIFY COLUMN key_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '凭证查找标识，全局唯一且不属于秘密',
    MODIFY COLUMN secret_digest BINARY(32) NOT NULL COMMENT '凭证Secret的SHA-256摘要，固定32字节',
    MODIFY COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态代码：1=有效，3=已撤销',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    MODIFY COLUMN last_used_at DATETIME(6) NULL COMMENT '最近一次成功使用时间，UTC，可为空',
    MODIFY COLUMN revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC，可为空',
    MODIFY COLUMN created_by BIGINT NOT NULL COMMENT '创建管理员ID，逻辑外键',
    MODIFY COLUMN revoked_by BIGINT NULL COMMENT '撤销管理员ID，逻辑外键，可为空';

ALTER TABLE application_grant
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '应用知识库授权主键ID',
    MODIFY COLUMN tenant_id BIGINT NOT NULL COMMENT '授权所属租户ID，逻辑外键',
    MODIFY COLUMN application_id BIGINT NOT NULL COMMENT '被授权应用ID，逻辑外键',
    MODIFY COLUMN knowledge_base_id BIGINT NOT NULL COMMENT '目标知识库ID，逻辑外键',
    MODIFY COLUMN permission VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权限代码：1=只读，2=只写，3=读写',
    MODIFY COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态代码：1=有效，3=已撤销',
    MODIFY COLUMN granted_at DATETIME(6) NOT NULL COMMENT '当前授权生效时间，UTC',
    MODIFY COLUMN granted_by BIGINT NOT NULL COMMENT '当前授权管理员ID，逻辑外键',
    MODIFY COLUMN revoked_at DATETIME(6) NULL COMMENT '最近一次撤销时间，UTC，可为空',
    MODIFY COLUMN revoked_by BIGINT NULL COMMENT '最近一次撤销管理员ID，逻辑外键，可为空';
