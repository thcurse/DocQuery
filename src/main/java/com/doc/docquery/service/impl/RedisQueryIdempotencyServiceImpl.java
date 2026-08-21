package com.doc.docquery.service.impl;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryIdempotencyException;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.config.QueryIdempotencyProperties;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.service.QueryIdempotencyService;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.doc.docquery.cache.QueryIdempotencyException.Reason.CONTEXT_CHANGED;
import static com.doc.docquery.cache.QueryIdempotencyException.Reason.CORRUPTED_STATE;
import static com.doc.docquery.cache.QueryIdempotencyException.Reason.IDEMPOTENCY_CONFLICT;
import static com.doc.docquery.cache.QueryIdempotencyException.Reason.INVALID_REQUEST;
import static com.doc.docquery.cache.QueryIdempotencyException.Reason.OWNERSHIP_LOST;
import static com.doc.docquery.cache.QueryIdempotencyException.Reason.RESULT_TOO_LARGE;
import static com.doc.docquery.cache.QueryIdempotencyException.Reason.UNAVAILABLE;

/**
 * 基于 Redis Hash 和 Lua 的查询幂等实现。
 *
 * <p>竞争、状态比较和 TTL 更新都在 Redis 内原子完成。owner token 防止租约过期
 * 后的旧执行者覆盖新执行者；原始 Idempotency-Key 只参与本地 SHA-256，不进入
 * Redis Value 或异常。</p>
 */
@Service
public class RedisQueryIdempotencyServiceImpl implements QueryIdempotencyService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY_PREFIX = Pattern.compile("[A-Za-z0-9:_-]{1,128}");

    private static final long CLAIM_OWNER = 1L;
    private static final long CLAIM_IN_PROGRESS = 2L;
    private static final long CLAIM_REPLAY = 3L;
    private static final long CLAIM_CONFLICT = -1L;
    private static final long CLAIM_CONTEXT_CHANGED = -2L;

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('HSET', KEYS[1],
                'state', 'RUNNING',
                'requestFingerprint', ARGV[1],
                'snapshotFingerprint', ARGV[2],
                'ownerToken', ARGV[3])
              redis.call('PEXPIRE', KEYS[1], ARGV[4])
              return 1
            end
            local requestFingerprint = redis.call('HGET', KEYS[1], 'requestFingerprint')
            local snapshotFingerprint = redis.call('HGET', KEYS[1], 'snapshotFingerprint')
            if not requestFingerprint or not snapshotFingerprint then return -3 end
            if requestFingerprint ~= ARGV[1] then return -1 end
            if snapshotFingerprint ~= ARGV[2] then return -2 end
            local state = redis.call('HGET', KEYS[1], 'state')
            if state == 'RUNNING' then return 2 end
            if state == 'SUCCEEDED' then return 3 end
            return -3
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING' then return 0 end
            if redis.call('HGET', KEYS[1], 'requestFingerprint') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'snapshotFingerprint') ~= ARGV[2] then return 0 end
            if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[3] then return 0 end
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING' then return 0 end
            if redis.call('HGET', KEYS[1], 'requestFingerprint') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'snapshotFingerprint') ~= ARGV[2] then return 0 end
            if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[3] then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'SUCCEEDED', 'result', ARGV[4])
            redis.call('HDEL', KEYS[1], 'ownerToken')
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'RUNNING' then return 0 end
            if redis.call('HGET', KEYS[1], 'requestFingerprint') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'snapshotFingerprint') ~= ARGV[2] then return 0 end
            if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[3] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final QueryIdempotencyProperties properties;

    public RedisQueryIdempotencyServiceImpl(
            StringRedisTemplate redisTemplate,
            QueryIdempotencyProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        validateProperties(properties);
    }

    @Override
    public QueryIdempotencyClaim claim(
            QueryAccessContext accessContext,
            QueryOperation operation,
            String idempotencyKey,
            String requestFingerprint
    ) {
        validateClaimRequest(accessContext, operation, idempotencyKey, requestFingerprint);
        requireEnabled();

        String storageKey = storageKey(accessContext, operation, idempotencyKey);
        String ownerToken = UUID.randomUUID().toString();
        try {
            Long result = redisTemplate.execute(
                    CLAIM_SCRIPT,
                    List.of(storageKey),
                    requestFingerprint,
                    accessContext.getSnapshotFingerprint(),
                    ownerToken,
                    Long.toString(properties.getRunningTtl().toMillis())
            );
            if (result == null) {
                throw unavailable(null);
            }
            if (result == CLAIM_OWNER) {
                return QueryIdempotencyClaim.owner(
                        storageKey,
                        ownerToken,
                        requestFingerprint,
                        accessContext.getSnapshotFingerprint()
                );
            }
            if (result == CLAIM_IN_PROGRESS) {
                return QueryIdempotencyClaim.inProgress(
                        storageKey,
                        requestFingerprint,
                        accessContext.getSnapshotFingerprint()
                );
            }
            if (result == CLAIM_REPLAY) {
                HashOperations<String, String, String> hashes = redisTemplate.opsForHash();
                String replayResult = hashes.get(storageKey, "result");
                if (replayResult == null) {
                    throw new QueryIdempotencyException(
                            CORRUPTED_STATE,
                            "Query idempotency result is incomplete"
                    );
                }
                return QueryIdempotencyClaim.replay(
                        storageKey,
                        requestFingerprint,
                        accessContext.getSnapshotFingerprint(),
                        replayResult
                );
            }
            if (result == CLAIM_CONFLICT) {
                throw new QueryIdempotencyException(
                        IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key is already bound to another request"
                );
            }
            if (result == CLAIM_CONTEXT_CHANGED) {
                throw new QueryIdempotencyException(
                        CONTEXT_CHANGED,
                        "Idempotency-Key is bound to another active-version snapshot"
                );
            }
            throw new QueryIdempotencyException(
                    CORRUPTED_STATE,
                    "Query idempotency state is invalid"
            );
        } catch (QueryIdempotencyException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean renew(QueryIdempotencyClaim claim) {
        validateOwnerClaim(claim);
        requireEnabled();
        try {
            Long result = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(claim.getStorageKey()),
                    claim.getRequestFingerprint(),
                    claim.getSnapshotFingerprint(),
                    claim.getOwnerToken(),
                    Long.toString(properties.getRunningTtl().toMillis())
            );
            return Long.valueOf(1L).equals(result);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void complete(QueryIdempotencyClaim claim, String responseJson) {
        validateOwnerClaim(claim);
        if (responseJson == null) {
            throw invalidRequest("Successful query response is required");
        }
        if (responseJson.getBytes(StandardCharsets.UTF_8).length
                > properties.getMaxResultBytes()) {
            throw new QueryIdempotencyException(
                    RESULT_TOO_LARGE,
                    "Successful query response exceeds Redis result limit"
            );
        }
        requireEnabled();
        try {
            Long result = redisTemplate.execute(
                    COMPLETE_SCRIPT,
                    List.of(claim.getStorageKey()),
                    claim.getRequestFingerprint(),
                    claim.getSnapshotFingerprint(),
                    claim.getOwnerToken(),
                    responseJson,
                    Long.toString(properties.getResultTtl().toMillis())
            );
            if (!Long.valueOf(1L).equals(result)) {
                throw new QueryIdempotencyException(
                        OWNERSHIP_LOST,
                        "Query idempotency ownership has expired"
                );
            }
        } catch (QueryIdempotencyException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void release(QueryIdempotencyClaim claim) {
        validateOwnerClaim(claim);
        requireEnabled();
        try {
            redisTemplate.execute(
                    RELEASE_SCRIPT,
                    List.of(claim.getStorageKey()),
                    claim.getRequestFingerprint(),
                    claim.getSnapshotFingerprint(),
                    claim.getOwnerToken()
            );
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void validateClaimRequest(
            QueryAccessContext accessContext,
            QueryOperation operation,
            String idempotencyKey,
            String requestFingerprint
    ) {
        if (accessContext == null
                || accessContext.getTenantId() == null
                || accessContext.getApplicationId() == null
                || accessContext.getKnowledgeBaseId() == null
                || accessContext.getSnapshotFingerprint() == null
                || !SHA_256.matcher(accessContext.getSnapshotFingerprint()).matches()
                || operation == null
                || idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || requestFingerprint == null
                || !SHA_256.matcher(requestFingerprint).matches()) {
            throw invalidRequest("Query idempotency request is invalid");
        }
    }

    private void validateOwnerClaim(QueryIdempotencyClaim claim) {
        if (claim == null
                || claim.getStatus() != QueryIdempotencyClaim.Status.OWNER
                || claim.getStorageKey() == null
                || claim.getOwnerToken() == null
                || claim.getRequestFingerprint() == null
                || !SHA_256.matcher(claim.getRequestFingerprint()).matches()
                || claim.getSnapshotFingerprint() == null
                || !SHA_256.matcher(claim.getSnapshotFingerprint()).matches()) {
            throw invalidRequest("Query idempotency owner claim is invalid");
        }
    }

    private String storageKey(
            QueryAccessContext accessContext,
            QueryOperation operation,
            String idempotencyKey
    ) {
        return properties.getKeyPrefix()
                + ':' + accessContext.getTenantId()
                + ':' + accessContext.getApplicationId()
                + ':' + operation.keyPart()
                + ':' + sha256(idempotencyKey);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw unavailable(null);
        }
    }

    private void validateProperties(QueryIdempotencyProperties candidate) {
        if (candidate.getKeyPrefix() == null
                || !KEY_PREFIX.matcher(candidate.getKeyPrefix()).matches()
                || !positive(candidate.getRunningTtl())
                || !positive(candidate.getResultTtl())
                || candidate.getMaxResultBytes() < 1) {
            throw new IllegalStateException("Query idempotency configuration is invalid");
        }
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private QueryIdempotencyException invalidRequest(String message) {
        return new QueryIdempotencyException(INVALID_REQUEST, message);
    }

    private QueryIdempotencyException unavailable(Throwable cause) {
        if (cause == null) {
            return new QueryIdempotencyException(
                    UNAVAILABLE,
                    "Query idempotency is unavailable"
            );
        }
        return new QueryIdempotencyException(
                UNAVAILABLE,
                "Query idempotency is unavailable",
                cause
        );
    }
}
