package com.doc.docquery;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DatabaseStartupIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void emptyDatabaseMigratesToN4Point2Schema() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                ORDER BY table_name
                """,
                String.class
        );

        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class
        );

        assertThat(tables).containsExactly(
                "admin_user",
                "application",
                "application_grant",
                "application_query_audit",
                "credential",
                "document",
                "document_canonical_artifact",
                "document_deletion_job",
                "document_retrieval_artifact",
                "document_search_projection",
                "document_version",
                "flyway_schema_history",
                "knowledge_base",
                "outbox_event",
                "processing_job",
                "tenant"
        );
        assertThat(successfulMigrations).isEqualTo(11);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    @Test
    void schemaContainsNoForeignKeysOrCheckConstraints() {
        Integer foreignKeys = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE()
                """,
                Integer.class
        );

        Integer checkConstraints = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_type = 'CHECK'
                """,
                Integer.class
        );

        assertThat(foreignKeys).isZero();
        assertThat(checkConstraints).isZero();
    }

    @Test
    void everyBusinessColumnHasCommentAndCodeCommentsDescribeValues() {
        Integer columnsWithoutComment = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                      'tenant',
                      'admin_user',
                      'application',
                      'knowledge_base',
                      'credential',
                      'application_grant',
                      'application_query_audit',
                      'document',
                      'document_canonical_artifact',
                      'document_deletion_job',
                      'document_retrieval_artifact',
                      'document_search_projection',
                      'document_version',
                      'processing_job',
                      'outbox_event'
                  )
                  AND column_comment = ''
                """,
                Integer.class
        );
        assertThat(columnsWithoutComment).isZero();

        assertThat(columnComment("admin_user", "role"))
                .contains("1=平台管理员", "2=租户管理员");
        assertThat(columnComment("tenant", "status"))
                .contains("1=启用", "2=停用");
        assertThat(columnComment("credential", "status"))
                .contains("1=有效", "3=已撤销");
        assertThat(columnComment("application_grant", "permission"))
                .contains("1=只读", "2=只写", "3=读写");
        assertThat(columnComment("application_grant", "status"))
                .contains("1=有效", "3=已撤销");
        assertThat(columnComment("document", "status"))
                .contains("1=正常", "2=删除中", "3=已删除");
        assertThat(columnComment("document_version", "status"))
                .contains("1=处理中", "2=可检索", "3=处理失败");
        assertThat(columnComment("document_version", "source_format"))
                .contains("1=PDF", "2=DOCX", "3=TXT", "4=Markdown");
        assertThat(columnComment("document_deletion_job", "status"))
                .contains("1=待处理", "2=处理中", "3=成功", "4=失败");
        assertThat(columnComment("processing_job", "status"))
                .contains("1=待处理", "2=处理中", "3=成功", "4=失败");
        assertThat(columnComment("outbox_event", "status"))
                .contains("1=待发布", "2=发布中", "3=已发送");

        List<String> stringCodeColumns = jdbcTemplate.queryForList(
                """
                SELECT CONCAT(table_name, '.', column_name, '.', data_type)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND (
                      column_name = 'status'
                      OR (table_name = 'admin_user' AND column_name = 'role')
                      OR (table_name = 'application_grant' AND column_name = 'permission')
                      OR (table_name = 'document_version' AND column_name = 'source_format')
                      OR (table_name = 'processing_job' AND column_name = 'job_type')
                      OR (table_name = 'outbox_event' AND column_name = 'event_type')
                  )
                  AND table_name IN (
                      'tenant',
                      'admin_user',
                      'application',
                      'knowledge_base',
                      'credential',
                      'application_grant',
                      'document',
                      'document_canonical_artifact',
                      'document_deletion_job',
                      'document_retrieval_artifact',
                      'document_search_projection',
                      'document_version',
                      'processing_job',
                      'outbox_event'
                  )
                ORDER BY table_name, column_name
                """,
                String.class
        );
        assertThat(stringCodeColumns).containsExactly(
                "admin_user.role.varchar",
                "admin_user.status.varchar",
                "application.status.varchar",
                "application_grant.permission.varchar",
                "application_grant.status.varchar",
                "credential.status.varchar",
                "document.status.varchar",
                "document_deletion_job.status.varchar",
                "document_version.source_format.varchar",
                "document_version.status.varchar",
                "knowledge_base.status.varchar",
                "outbox_event.event_type.varchar",
                "outbox_event.status.varchar",
                "processing_job.job_type.varchar",
                "processing_job.status.varchar",
                "tenant.status.varchar"
        );
    }

    @Test
    void primaryKeysUseAutoIncrementBigint() {
        List<String> primaryKeys = jdbcTemplate.queryForList(
                """
                SELECT CONCAT(table_name, '.', data_type, '.', extra)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND column_name = 'id'
                  AND table_name IN (
                      'tenant',
                      'admin_user',
                      'application',
                      'knowledge_base',
                      'credential',
                      'application_grant',
                      'document',
                      'document_canonical_artifact',
                      'document_deletion_job',
                      'document_retrieval_artifact',
                      'document_search_projection',
                      'document_version',
                      'processing_job',
                      'outbox_event'
                  )
                ORDER BY table_name
                """,
                String.class
        );

        assertThat(primaryKeys).containsExactly(
                "admin_user.bigint.auto_increment",
                "application.bigint.auto_increment",
                "application_grant.bigint.auto_increment",
                "credential.bigint.auto_increment",
                "document.bigint.auto_increment",
                "document_canonical_artifact.bigint.auto_increment",
                "document_deletion_job.bigint.auto_increment",
                "document_retrieval_artifact.bigint.auto_increment",
                "document_search_projection.bigint.auto_increment",
                "document_version.bigint.auto_increment",
                "knowledge_base.bigint.auto_increment",
                "outbox_event.bigint.auto_increment",
                "processing_job.bigint.auto_increment",
                "tenant.bigint.auto_increment"
        );
    }

    @Test
    void logicalRelationshipColumnsHaveQueryIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
                """
                SELECT CONCAT(table_name, '.', index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND index_name <> 'PRIMARY'
                ORDER BY table_name, index_name
                """,
                String.class
        );

        assertThat(indexes).contains(
                "admin_user.idx_admin_user_tenant_id",
                "application.idx_application_tenant_status",
                "application_grant.idx_application_grant_knowledge_base_id",
                "application_grant.idx_application_grant_tenant_id",
                "credential.idx_credential_application_status",
                "document.idx_document_tenant_knowledge_base_status_id",
                "document.idx_document_tenant_kb_updated_id",
                "document_canonical_artifact.idx_document_canonical_artifact_tenant_version",
                "document_deletion_job.idx_document_deletion_job_status_lease_id",
                "document_deletion_job.idx_document_deletion_job_tenant_document_attempt",
                "document_retrieval_artifact.idx_document_retrieval_artifact_canonical",
                "document_retrieval_artifact.idx_document_retrieval_artifact_tenant_version",
                "document_search_projection.idx_document_search_projection_canonical",
                "document_search_projection.idx_document_search_projection_retrieval",
                "document_search_projection.idx_document_search_projection_tenant_kb_version",
                "document_version.idx_document_version_tenant_document_status_version",
                "document_version.idx_document_version_tenant_document_version_id",
                "knowledge_base.idx_knowledge_base_tenant_status",
                "outbox_event.idx_outbox_event_status_available_lock_id",
                "processing_job.idx_processing_job_status_lease_id",
                "processing_job.idx_processing_job_tenant_status_updated_id",
                "processing_job.idx_processing_job_tenant_updated_id",
                "processing_job.uk_processing_job_tenant_idempotency"
        );
    }

    @Test
    void uniqueIndexesRejectDuplicateBusinessKeys() {
        long tenantId = 1001L;
        long applicationId = 2001L;
        long knowledgeBaseId = 3001L;
        long adminId = 4001L;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        insertTenant(tenantId, "Unique Tenant", now);
        insertAdmin(adminId, tenantId, "unique-admin", now);
        insertApplication(applicationId, tenantId, "unique-app", "Unique App", now);
        insertKnowledgeBase(knowledgeBaseId, tenantId, "Unique Knowledge Base", now);
        insertCredential(
                5001L,
                applicationId,
                "unique-key-id",
                adminId,
                now
        );
        insertGrant(
                6001L,
                tenantId,
                applicationId,
                knowledgeBaseId,
                adminId,
                now
        );

        assertThatThrownBy(() -> insertAdmin(
                4002L,
                tenantId,
                "unique-admin",
                now
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertApplication(
                2002L,
                tenantId,
                "unique-app",
                "Another App",
                now
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertKnowledgeBase(
                3002L,
                tenantId,
                "Unique Knowledge Base",
                now
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertCredential(
                5002L,
                applicationId,
                "unique-key-id",
                adminId,
                now
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertGrant(
                6002L,
                tenantId,
                applicationId,
                knowledgeBaseId,
                adminId,
                now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tenantScopedNamesAllowDifferentTenantsAndKnowledgeBaseNameIsCaseSensitive() {
        long firstTenantId = 1010L;
        long secondTenantId = 1011L;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        insertTenant(firstTenantId, "First Tenant", now);
        insertTenant(secondTenantId, "Second Tenant", now);

        insertApplication(
                2010L,
                firstTenantId,
                "shared-code",
                "First Shared App",
                now
        );
        insertApplication(
                2011L,
                secondTenantId,
                "shared-code",
                "Second Shared App",
                now
        );

        insertKnowledgeBase(
                3010L,
                firstTenantId,
                "Service Manual",
                now
        );
        insertKnowledgeBase(
                3011L,
                secondTenantId,
                "Service Manual",
                now
        );
        insertKnowledgeBase(
                3012L,
                firstTenantId,
                "service manual",
                now
        );

        Integer applicationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application WHERE code = 'shared-code'",
                Integer.class
        );
        Integer knowledgeBaseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE LOWER(name) = 'service manual'",
                Integer.class
        );

        assertThat(applicationCount).isEqualTo(2);
        assertThat(knowledgeBaseCount).isEqualTo(3);
    }

    private void insertTenant(long id, String name, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO tenant (id, name, status, created_at, updated_at)
                VALUES (?, ?, '1', ?, ?)
                """,
                id,
                name,
                now,
                now
        );
    }

    private void insertAdmin(
            long id,
            long tenantId,
            String loginName,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    id, tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, ?, ?, '{bcrypt}test-only-hash', '2', '1', ?, ?)
                """,
                id,
                tenantId,
                loginName,
                now,
                now
        );
    }

    private void insertApplication(
            long id,
            long tenantId,
            String code,
            String name,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO application (
                    id, tenant_id, code, name, environment, description, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PRODUCTION', NULL, '1', ?, ?)
                """,
                id,
                tenantId,
                code,
                name,
                now,
                now
        );
    }

    private void insertKnowledgeBase(
            long id,
            long tenantId,
            String name,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    id, tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, ?, ?, NULL, '1', ?, ?)
                """,
                id,
                tenantId,
                name,
                now,
                now
        );
    }

    private void insertCredential(
            long id,
            long applicationId,
            String keyId,
            long createdBy,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO credential (
                    id, application_id, name, key_id, secret_digest, status,
                    created_at, last_used_at, revoked_at, created_by, revoked_by
                ) VALUES (?, ?, 'Test Credential', ?, ?, '1', ?, NULL, NULL, ?, NULL)
                """,
                id,
                applicationId,
                keyId,
                new byte[32],
                now,
                createdBy
        );
    }

    private void insertGrant(
            long id,
            long tenantId,
            long applicationId,
            long knowledgeBaseId,
            long grantedBy,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO application_grant (
                    id, tenant_id, application_id, knowledge_base_id, permission, status,
                    granted_at, granted_by, revoked_at, revoked_by
                ) VALUES (?, ?, ?, ?, '1', '1', ?, ?, NULL, NULL)
                """,
                id,
                tenantId,
                applicationId,
                knowledgeBaseId,
                now,
                grantedBy
        );
    }

    private String columnComment(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT column_comment
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """,
                String.class,
                tableName,
                columnName
        );
    }
}
