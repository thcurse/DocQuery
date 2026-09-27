package com.doc.docquery.stream;
import com.doc.docquery.config.AnswerProperties;
import com.doc.docquery.config.QueryIdempotencyProperties;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AnswerStreamCapacityTest {
    @Test void busyStreamReleasesPreparedOwnershipWithoutExecutingIt() throws Exception {
        var properties = new AnswerProperties();
        properties.setStreamConcurrency(1);
        var runner = new AnswerStreamRunner(properties, new QueryIdempotencyProperties());
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var first = mock(PreparedAnswer.class);
        when(first.execute(any())).thenAnswer(call -> {
            started.countDown();
            finish.await(3, TimeUnit.SECONDS);
            throw AnswerStreamException.cancelled();
        });
        try {
            runner.open("one", () -> first);
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();
            var rejected = mock(PreparedAnswer.class);
            assertThatThrownBy(() -> runner.open("two", () -> rejected))
                    .isInstanceOf(AnswerStreamException.class).hasMessageContaining("繁忙");
            verify(rejected).reject(any(AnswerStreamException.class));
            verify(rejected, never()).execute(any());
        } finally { finish.countDown(); runner.close(); }
    }
    @Test void slowConsumerCancelsProducerAndReleasesCapacityAfterWriterExits() throws Exception {
        var properties = new AnswerProperties();
        properties.setStreamConcurrency(1);
        properties.setStreamQueueCapacity(2);
        var writing = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var failureCode = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var emitters = mockConstruction(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class,
                (emitter, context) -> doAnswer(call -> {
                    writing.countDown();
                    if (!releaseWriter.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("test timeout");
                    return null;
                }).when(emitter).send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class)))) {
            var runner = new AnswerStreamRunner(properties, new QueryIdempotencyProperties());
            var first = mock(PreparedAnswer.class);
            when(first.execute(any())).thenAnswer(call -> {
                AnswerExecution execution = call.getArgument(0);
                execution.onCancel(cancelled::countDown);
                try {
                    if (!writing.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("writer not started");
                    for (int i = 0; i < 20; i++) execution.delta("increment");
                    throw new AssertionError("slow consumer did not stop producer");
                } catch (AnswerStreamException failure) {
                    failureCode.set(failure.code());
                    throw failure;
                } finally { exited.countDown(); }
            });
            try {
                runner.open("slow", () -> first);
                assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(failureCode.get()).isEqualTo("ANSWER_STREAM_BACKPRESSURE");
                verify(first, timeout(1000)).abandon();
                releaseWriter.countDown();
                var next = mock(PreparedAnswer.class);
                var admitted = new CountDownLatch(1);
                when(next.execute(any())).thenAnswer(call -> {
                    admitted.countDown();
                    throw AnswerStreamException.cancelled();
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                boolean opened = false;
                while (!opened && System.nanoTime() < deadline) {
                    try { runner.open("next", () -> next); opened = true; }
                    catch (AnswerStreamException busy) { Thread.sleep(10); }
                }
                assertThat(opened).isTrue();
                assertThat(admitted.await(1, TimeUnit.SECONDS)).isTrue();
            } finally { releaseWriter.countDown(); runner.close(); }
        }
    }

}
