package com.doc.docquery.service.impl;

import com.doc.docquery.cache.QueryIdempotencyClaim;
import com.doc.docquery.cache.QueryIdempotencyException;
import com.doc.docquery.cache.QueryOperation;
import com.doc.docquery.config.QueryIdempotencyProperties;
import com.doc.docquery.security.QueryAccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

class RedisQueryIdempotencyServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void replayUsesTheResultReadAtomicallyWithItsFingerprints() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        String response = "{\"answer\":\"原请求的结果\\n第二行\"}";
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(3L, response));
        QueryIdempotencyProperties properties = new QueryIdempotencyProperties();
        properties.setEnabled(true);
        RedisQueryIdempotencyServiceImpl service = new RedisQueryIdempotencyServiceImpl(
                redisTemplate, properties
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 2L, 3L, 4L, "1", List.of(), "a".repeat(64)
        );

        QueryIdempotencyClaim claim = service.claim(
                context, QueryOperation.RETRIEVE, "safe-key", "b".repeat(64)
        );

        assertThat(claim.getStatus()).isEqualTo(QueryIdempotencyClaim.Status.REPLAY);
        assertThat(claim.getReplayResult()).isEqualTo(response);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
        // 即使脚本返回后 Key 被过期重用，也不再另发 HGET 读取其他执行者的响应。
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompleteAtomicReplayFailsClosed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(3L));
        QueryIdempotencyProperties properties = new QueryIdempotencyProperties();
        properties.setEnabled(true);
        RedisQueryIdempotencyServiceImpl service = new RedisQueryIdempotencyServiceImpl(
                redisTemplate, properties
        );
        QueryAccessContext context = new QueryAccessContext(
                1L, 2L, 3L, 4L, "1", List.of(), "a".repeat(64)
        );

        QueryIdempotencyException exception = catchThrowableOfType(
                QueryIdempotencyException.class,
                () -> service.claim(context, QueryOperation.RETRIEVE, "safe-key", "b".repeat(64))
        );

        assertThat(exception).isNotNull();
        assertThat(exception.reason()).isEqualTo(QueryIdempotencyException.Reason.CORRUPTED_STATE);
    }

    @Test
    void disabledInfrastructureFailsClosedBeforeRedisIsUsed() {
        QueryIdempotencyProperties properties = new QueryIdempotencyProperties();
        properties.setEnabled(false);
        RedisQueryIdempotencyServiceImpl service = new RedisQueryIdempotencyServiceImpl(
                mock(StringRedisTemplate.class),
                properties
        );
        QueryAccessContext context = new QueryAccessContext(
                1L,
                2L,
                3L,
                4L,
                "1",
                List.of(),
                "a".repeat(64)
        );

        QueryIdempotencyException exception = catchThrowableOfType(
                QueryIdempotencyException.class,
                () -> service.claim(
                        context,
                        QueryOperation.RETRIEVE,
                        "safe-key",
                        "b".repeat(64)
                )
        );

        assertThat(exception).isNotNull();
        assertThat(exception.reason()).isEqualTo(
                QueryIdempotencyException.Reason.UNAVAILABLE
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisConnectionFailureIsMappedToStableUnavailableReason() {
        QueryIdempotencyProperties properties = new QueryIdempotencyProperties();
        properties.setEnabled(true);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.any(RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenThrow(new RedisConnectionFailureException("test-only unavailable"));
        RedisQueryIdempotencyServiceImpl service = new RedisQueryIdempotencyServiceImpl(
                redisTemplate,
                properties
        );
        QueryAccessContext context = new QueryAccessContext(
                1L,
                2L,
                3L,
                4L,
                "1",
                List.of(),
                "a".repeat(64)
        );

        QueryIdempotencyException exception = catchThrowableOfType(
                QueryIdempotencyException.class,
                () -> service.claim(
                        context,
                        QueryOperation.RETRIEVE,
                        "safe-key",
                        "b".repeat(64)
                )
        );

        assertThat(exception).isNotNull();
        assertThat(exception.reason()).isEqualTo(
                QueryIdempotencyException.Reason.UNAVAILABLE
        );
        assertThat(exception.getCause()).isInstanceOf(RedisConnectionFailureException.class);
    }
}
