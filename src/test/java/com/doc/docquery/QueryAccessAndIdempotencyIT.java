package com.doc.docquery;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryIdempotencyException;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.security.QueryAccessException;
import com.doc.docquery.service.QueryAccessService;
import com.doc.docquery.service.QueryIdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** N3.1 在真实 MySQL 和 Redis 上验收授权快照、隔离及原子幂等。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "docquery.query.idempotency.enabled=true",
                "docquery.query.idempotency.running-ttl=30s",
                "docquery.query.idempotency.result-ttl=30s",
                "docquery.query.idempotency.max-result-bytes=128"
        }
)
class QueryAccessAndIdempotencyIT {

    private static final String REDIS_PASSWORD = "n3-1-test-password";
    private static final String KEY_ID_A = "A".repeat(22);
    private static final String SECRET_A = "a".repeat(43);
    private static final String TOKEN_A = "dq_app_" + KEY_ID_A + '.' + SECRET_A;
    private static final String KEY_ID_A2 = "B".repeat(22);
    private static final String SECRET_A2 = "b".repeat(43);
    private static final String TOKEN_A2 = "dq_app_" + KEY_ID_A2 + '.' + SECRET_A2;

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;
    private static final long ADMIN_A = 2001L;
    private static final long ADMIN_B = 2002L;
    private static final long APPLICATION_A = 3001L;
    private static final long APPLICATION_A2 = 3002L;
    private static final long APPLICATION_B = 3003L;
    private static final long CREDENTIAL_A = 4001L;
    private static final long CREDENTIAL_A2 = 4002L;
    private static final long KNOWLEDGE_BASE_A = 5001L;
    private static final long KNOWLEDGE_BASE_A2 = 5002L;
    private static final long KNOWLEDGE_BASE_B = 5003L;

    private static final long DOCUMENT_ONE = 6001L;
    private static final long DOCUMENT_TWO = 6002L;
    private static final long DOCUMENT_PROCESSING = 6003L;
    private static final long DOCUMENT_DELETING = 6004L;
    private static final long VERSION_ONE_OLD = 7001L;
    private static final long VERSION_ONE_ACTIVE = 7002L;
    private static final long VERSION_TWO_ACTIVE = 7003L;
    private static final long VERSION_PROCESSING = 7004L;
    private static final long VERSION_DELETING = 7005L;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10")
            .withDatabaseName("docquery")
            .withUsername("docquery")
            .withPassword("test-only-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.8.1")
    )
            .withExposedPorts(6379)
            .withCommand(
                    "redis-server",
                    "--appendonly", "no",
                    "--save", "",
                    "--requirepass", REDIS_PASSWORD
            )
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private QueryAccessService queryAccessService;

    @Autowired
    private QueryIdempotencyService idempotencyService;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(12);
        Set<String> redisKeys = redisTemplate.keys("*");
        if (redisKeys != null && !redisKeys.isEmpty()) {
            redisTemplate.delete(redisKeys);
        }
        resetDatabase();
        insertFixture();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void authorizationProducesOneDeterministicImmutableActiveVersionSnapshot() {
        QueryAccessContext first = authorize(TOKEN_A, KNOWLEDGE_BASE_A);

        assertThat(first.getCredentialId()).isEqualTo(CREDENTIAL_A);
        assertThat(first.getApplicationId()).isEqualTo(APPLICATION_A);
        assertThat(first.getTenantId()).isEqualTo(TENANT_A);
        assertThat(first.getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_A);
        assertThat(first.getGrantedPermission()).isEqualTo("1");
        assertThat(first.getActiveVersions())
                .extracting(
                        ActiveDocumentVersionSnapshot::documentId,
                        ActiveDocumentVersionSnapshot::documentVersionId
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DOCUMENT_ONE, VERSION_ONE_ACTIVE),
                        org.assertj.core.groups.Tuple.tuple(DOCUMENT_TWO, VERSION_TWO_ACTIVE)
                );
        assertThat(first.getSnapshotFingerprint()).matches("[0-9a-f]{64}");
        assertThat(authorize(TOKEN_A, KNOWLEDGE_BASE_A).getSnapshotFingerprint())
                .isEqualTo(first.getSnapshotFingerprint());

        Throwable immutableFailure = catchThrowableOfType(
                UnsupportedOperationException.class,
                () -> first.getActiveVersions().clear()
        );
        assertThat(immutableFailure).isNotNull();

        jdbcTemplate.update(
                "UPDATE document SET active_version_id = ?, updated_at = ? WHERE id = ?",
                VERSION_ONE_OLD,
                LocalDateTime.now(),
                DOCUMENT_ONE
        );
        QueryAccessContext changed = authorize(TOKEN_A, KNOWLEDGE_BASE_A);
        assertThat(changed.getActiveVersions().get(0).documentVersionId())
                .isEqualTo(VERSION_ONE_OLD);
        assertThat(changed.getSnapshotFingerprint())
                .isNotEqualTo(first.getSnapshotFingerprint());
        assertThat(first.getActiveVersions().get(0).documentVersionId())
                .isEqualTo(VERSION_ONE_ACTIVE);
    }

    @Test
    void serviceBoundaryHidesKnowledgeBaseDetailsAndRechecksEveryStatus() {
        assertAccessFailure(null, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID);
        assertAccessFailure("not-a-token", KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID);
        assertAccessFailure(TOKEN_A.substring(0, TOKEN_A.length() - 1) + "z",
                KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID);

        assertAccessFailure(TOKEN_A, 999999L,
                QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE);
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A2,
                QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE);
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_B,
                QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE);

        jdbcTemplate.update(
                "UPDATE application_grant SET permission = '2' WHERE application_id = ? AND knowledge_base_id = ?",
                APPLICATION_A,
                KNOWLEDGE_BASE_A
        );
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE);

        jdbcTemplate.update(
                "UPDATE application_grant SET permission = '1', status = '3' WHERE application_id = ? AND knowledge_base_id = ?",
                APPLICATION_A,
                KNOWLEDGE_BASE_A
        );
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE);

        jdbcTemplate.update(
                "UPDATE application_grant SET status = '1' WHERE application_id = ? AND knowledge_base_id = ?",
                APPLICATION_A,
                KNOWLEDGE_BASE_A
        );
        jdbcTemplate.update("UPDATE knowledge_base SET status = '2' WHERE id = ?", KNOWLEDGE_BASE_A);
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.KNOWLEDGE_BASE_NOT_AVAILABLE);

        jdbcTemplate.update("UPDATE knowledge_base SET status = '1' WHERE id = ?", KNOWLEDGE_BASE_A);
        jdbcTemplate.update("UPDATE credential SET status = '3' WHERE id = ?", CREDENTIAL_A);
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID);

        jdbcTemplate.update("UPDATE credential SET status = '1' WHERE id = ?", CREDENTIAL_A);
        jdbcTemplate.update("UPDATE application SET status = '2' WHERE id = ?", APPLICATION_A);
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID);

        jdbcTemplate.update("UPDATE application SET status = '1' WHERE id = ?", APPLICATION_A);
        jdbcTemplate.update("UPDATE tenant SET status = '2' WHERE id = ?", TENANT_A);
        assertAccessFailure(TOKEN_A, KNOWLEDGE_BASE_A,
                QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID);
    }

    @Test
    void concurrentClaimsHaveOneOwnerAndSuccessfulResultIsReplayed() throws Exception {
        QueryAccessContext context = authorize(TOKEN_A, KNOWLEDGE_BASE_A);
        String rawKey = "n3.1-concurrent-key";
        String requestFingerprint = sha256("same normalized retrieve request");
        List<Callable<QueryIdempotencyClaim>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(() -> idempotencyService.claim(
                    context,
                    QueryOperation.RETRIEVE,
                    rawKey,
                    requestFingerprint
            ));
        }

        List<QueryIdempotencyClaim> claims = new ArrayList<>();
        for (Future<QueryIdempotencyClaim> future : executor.invokeAll(calls)) {
            claims.add(future.get());
        }
        assertThat(claims)
                .filteredOn(claim -> claim.getStatus() == QueryIdempotencyClaim.Status.OWNER)
                .hasSize(1);
        assertThat(claims)
                .filteredOn(claim -> claim.getStatus() == QueryIdempotencyClaim.Status.IN_PROGRESS)
                .hasSize(23);

        QueryIdempotencyClaim owner = claims.stream()
                .filter(claim -> claim.getStatus() == QueryIdempotencyClaim.Status.OWNER)
                .findFirst()
                .orElseThrow();
        assertThat(owner.getStorageKey()).doesNotContain(rawKey);
        assertThat(owner.getStorageKey()).doesNotContain(TOKEN_A);
        assertThat(redisTemplate.opsForHash().entries(owner.getStorageKey()).values())
                .noneMatch(value -> value.toString().contains(rawKey)
                        || value.toString().contains(TOKEN_A));
        assertThat(redisTemplate.getExpire(owner.getStorageKey()))
                .isBetween(1L, 30L);

        String response = "{\"requestId\":\"r-1\",\"evidence\":[]}";
        idempotencyService.complete(owner, response);
        QueryIdempotencyClaim replay = idempotencyService.claim(
                context,
                QueryOperation.RETRIEVE,
                rawKey,
                requestFingerprint
        );
        assertThat(replay.getStatus()).isEqualTo(QueryIdempotencyClaim.Status.REPLAY);
        assertThat(replay.getReplayResult()).isEqualTo(response);
        assertThat(redisTemplate.getExpire(owner.getStorageKey()))
                .isBetween(1L, 30L);
    }

    @Test
    void idempotencyScopeRejectsConflictsAndSeparatesApplicationAndOperation() {
        QueryAccessContext context = authorize(TOKEN_A, KNOWLEDGE_BASE_A);
        QueryAccessContext otherApplication = authorize(TOKEN_A2, KNOWLEDGE_BASE_A);
        String key = "scope-key";
        String request = sha256("request-a");
        QueryIdempotencyClaim owner = idempotencyService.claim(
                context,
                QueryOperation.RETRIEVE,
                key,
                request
        );

        assertIdempotencyFailure(
                () -> idempotencyService.claim(
                        context,
                        QueryOperation.RETRIEVE,
                        key,
                        sha256("request-b")
                ),
                QueryIdempotencyException.Reason.IDEMPOTENCY_CONFLICT
        );

        QueryAccessContext changedSnapshot = new QueryAccessContext(
                context.getCredentialId(),
                context.getApplicationId(),
                context.getTenantId(),
                context.getKnowledgeBaseId(),
                context.getGrantedPermission(),
                context.getActiveVersions(),
                sha256("another active snapshot")
        );
        assertIdempotencyFailure(
                () -> idempotencyService.claim(
                        changedSnapshot,
                        QueryOperation.RETRIEVE,
                        key,
                        request
                ),
                QueryIdempotencyException.Reason.CONTEXT_CHANGED
        );

        assertThat(idempotencyService.claim(
                context,
                QueryOperation.ANSWER,
                key,
                request
        ).getStatus()).isEqualTo(QueryIdempotencyClaim.Status.OWNER);
        assertThat(idempotencyService.claim(
                otherApplication,
                QueryOperation.RETRIEVE,
                key,
                request
        ).getStatus()).isEqualTo(QueryIdempotencyClaim.Status.OWNER);
        idempotencyService.release(owner);
    }

    @Test
    void ownerCanRenewAndReleaseWhileStaleOwnerCannotOverwrite() {
        QueryAccessContext context = authorize(TOKEN_A, KNOWLEDGE_BASE_A);
        String request = sha256("lease request");
        QueryIdempotencyClaim firstOwner = idempotencyService.claim(
                context,
                QueryOperation.RETRIEVE,
                "lease-key",
                request
        );
        assertThat(idempotencyService.renew(firstOwner)).isTrue();
        idempotencyService.release(firstOwner);

        QueryIdempotencyClaim secondOwner = idempotencyService.claim(
                context,
                QueryOperation.RETRIEVE,
                "lease-key",
                request
        );
        assertThat(secondOwner.getStatus()).isEqualTo(QueryIdempotencyClaim.Status.OWNER);
        assertThat(secondOwner.getOwnerToken()).isNotEqualTo(firstOwner.getOwnerToken());
        assertThat(idempotencyService.renew(firstOwner)).isFalse();
        assertIdempotencyFailure(
                () -> idempotencyService.complete(firstOwner, "{\"stale\":true}"),
                QueryIdempotencyException.Reason.OWNERSHIP_LOST
        );
        idempotencyService.complete(secondOwner, "{\"fresh\":true}");
        assertThat(idempotencyService.claim(
                context,
                QueryOperation.RETRIEVE,
                "lease-key",
                request
        ).getReplayResult()).isEqualTo("{\"fresh\":true}");
    }

    @Test
    void idempotencyRejectsInvalidKeysAndOversizedResults() {
        QueryAccessContext context = authorize(TOKEN_A, KNOWLEDGE_BASE_A);
        assertIdempotencyFailure(
                () -> idempotencyService.claim(
                        context,
                        QueryOperation.RETRIEVE,
                        "contains space",
                        sha256("request")
                ),
                QueryIdempotencyException.Reason.INVALID_REQUEST
        );

        QueryIdempotencyClaim owner = idempotencyService.claim(
                context,
                QueryOperation.RETRIEVE,
                "bounded-result-key",
                sha256("bounded request")
        );
        assertIdempotencyFailure(
                () -> idempotencyService.complete(owner, "界".repeat(43)),
                QueryIdempotencyException.Reason.RESULT_TOO_LARGE
        );
    }

    private QueryAccessContext authorize(String token, long knowledgeBaseId) {
        String header = token == null ? null : "Bearer " + token;
        return queryAccessService.authorizeAndSnapshot(header, knowledgeBaseId);
    }

    private void assertAccessFailure(
            String token,
            long knowledgeBaseId,
            QueryAccessException.Reason expectedReason
    ) {
        QueryAccessException exception = catchThrowableOfType(
                QueryAccessException.class,
                () -> authorize(token, knowledgeBaseId)
        );
        assertThat(exception).isNotNull();
        assertThat(exception.reason()).isEqualTo(expectedReason);
    }

    private void assertIdempotencyFailure(
            Runnable action,
            QueryIdempotencyException.Reason expectedReason
    ) {
        QueryIdempotencyException exception = catchThrowableOfType(
                QueryIdempotencyException.class,
                action::run
        );
        assertThat(exception).isNotNull();
        assertThat(exception.reason()).isEqualTo(expectedReason);
    }

    private void resetDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processing_job");
        jdbcTemplate.update("DELETE FROM document_search_projection");
        jdbcTemplate.update("DELETE FROM document_retrieval_artifact");
        jdbcTemplate.update("DELETE FROM document_canonical_artifact");
        jdbcTemplate.update("DELETE FROM document_version");
        jdbcTemplate.update("DELETE FROM document");
        jdbcTemplate.update("DELETE FROM application_grant");
        jdbcTemplate.update("DELETE FROM credential");
        jdbcTemplate.update("DELETE FROM knowledge_base");
        jdbcTemplate.update("DELETE FROM application");
        jdbcTemplate.update("DELETE FROM admin_user");
        jdbcTemplate.update("DELETE FROM tenant");
    }

    private void insertFixture() {
        LocalDateTime now = LocalDateTime.now();
        insertTenant(TENANT_A, "N3.1 Tenant A", now);
        insertTenant(TENANT_B, "N3.1 Tenant B", now);
        insertAdmin(ADMIN_A, TENANT_A, "n3.1-admin-a", now);
        insertAdmin(ADMIN_B, TENANT_B, "n3.1-admin-b", now);
        insertApplication(APPLICATION_A, TENANT_A, "n3-1-app-a", now);
        insertApplication(APPLICATION_A2, TENANT_A, "n3-1-app-a2", now);
        insertApplication(APPLICATION_B, TENANT_B, "n3-1-app-b", now);
        insertKnowledgeBase(KNOWLEDGE_BASE_A, TENANT_A, "N3.1 Knowledge A", now);
        insertKnowledgeBase(KNOWLEDGE_BASE_A2, TENANT_A, "N3.1 Knowledge A2", now);
        insertKnowledgeBase(KNOWLEDGE_BASE_B, TENANT_B, "N3.1 Knowledge B", now);
        insertCredential(CREDENTIAL_A, APPLICATION_A, KEY_ID_A, SECRET_A, ADMIN_A, now);
        insertCredential(CREDENTIAL_A2, APPLICATION_A2, KEY_ID_A2, SECRET_A2, ADMIN_A, now);
        insertGrant(8001L, TENANT_A, APPLICATION_A, KNOWLEDGE_BASE_A, ADMIN_A, now);
        insertGrant(8002L, TENANT_A, APPLICATION_A2, KNOWLEDGE_BASE_A, ADMIN_A, now);

        insertDocument(
                DOCUMENT_ONE,
                "Document One",
                "1",
                VERSION_ONE_ACTIVE,
                VERSION_ONE_ACTIVE,
                now
        );
        insertDocumentVersion(VERSION_ONE_OLD, DOCUMENT_ONE, 1, "2", now);
        insertDocumentVersion(VERSION_ONE_ACTIVE, DOCUMENT_ONE, 2, "2", now);
        insertDocument(
                DOCUMENT_TWO,
                "Document Two",
                "1",
                VERSION_TWO_ACTIVE,
                VERSION_TWO_ACTIVE,
                now
        );
        insertDocumentVersion(VERSION_TWO_ACTIVE, DOCUMENT_TWO, 1, "2", now);
        insertDocument(
                DOCUMENT_PROCESSING,
                "Processing Document",
                "1",
                null,
                VERSION_PROCESSING,
                now
        );
        insertDocumentVersion(VERSION_PROCESSING, DOCUMENT_PROCESSING, 1, "1", now);
        insertDocument(
                DOCUMENT_DELETING,
                "Deleting Document",
                "2",
                VERSION_DELETING,
                VERSION_DELETING,
                now
        );
        insertDocumentVersion(VERSION_DELETING, DOCUMENT_DELETING, 1, "2", now);
    }

    private void insertTenant(long id, String name, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO tenant (id, name, status, created_at, updated_at) VALUES (?, ?, '1', ?, ?)",
                id, name, now, now
        );
    }

    private void insertAdmin(long id, long tenantId, String loginName, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO admin_user (
                    id, tenant_id, login_name, password_hash, role, status, created_at, updated_at
                ) VALUES (?, ?, ?, '{bcrypt}test-only-hash', '2', '1', ?, ?)
                """,
                id, tenantId, loginName, now, now
        );
    }

    private void insertApplication(long id, long tenantId, String code, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO application (
                    id, tenant_id, code, name, environment, description, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'TEST', NULL, '1', ?, ?)
                """,
                id, tenantId, code, code, now, now
        );
    }

    private void insertKnowledgeBase(long id, long tenantId, String name, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_base (
                    id, tenant_id, name, description, status, created_at, updated_at
                ) VALUES (?, ?, ?, NULL, '1', ?, ?)
                """,
                id, tenantId, name, now, now
        );
    }

    private void insertCredential(
            long id,
            long applicationId,
            String keyId,
            String secret,
            long createdBy,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO credential (
                    id, application_id, name, key_id, secret_digest, status,
                    created_at, last_used_at, revoked_at, created_by, revoked_by
                ) VALUES (?, ?, 'N3.1 Credential', ?, ?, '1', ?, NULL, NULL, ?, NULL)
                """,
                id, applicationId, keyId, sha256Bytes(secret), now, createdBy
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
                id, tenantId, applicationId, knowledgeBaseId, now, grantedBy
        );
    }

    private void insertDocument(
            long id,
            String name,
            String status,
            Long activeVersionId,
            Long latestVersionId,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO document (
                    id, tenant_id, knowledge_base_id, name, status,
                    active_version_id, latest_version_id, created_by_admin_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                TENANT_A,
                KNOWLEDGE_BASE_A,
                name,
                status,
                activeVersionId,
                latestVersionId,
                ADMIN_A,
                now,
                now
        );
    }

    private void insertDocumentVersion(
            long id,
            long documentId,
            int versionNo,
            String status,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO document_version (
                    id, tenant_id, document_id, version_no, status, source_format,
                    original_filename, source_bucket, source_object_key, source_size_bytes,
                    source_sha256, source_content_type, idempotency_key_hash,
                    request_fingerprint, accepted_by_admin_id, failure_code,
                    failure_message, ready_at, failed_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, '3', ?, 'docquery', ?, 1,
                    ?, 'text/plain', ?, ?, ?, NULL,
                    NULL, ?, NULL, ?, ?
                )
                """,
                id,
                TENANT_A,
                documentId,
                versionNo,
                status,
                "document-" + documentId + "-v" + versionNo + ".txt",
                "tests/n3-1/" + id,
                sha256("source-" + id),
                sha256("idempotency-" + id),
                sha256("request-" + id),
                ADMIN_A,
                "2".equals(status) ? now : null,
                now,
                now
        );
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
