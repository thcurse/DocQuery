package com.doc.docquery.stream;

import com.doc.docquery.audit.QueryAuditFailureClassifier;
import com.doc.docquery.config.AnswerProperties;
import com.doc.docquery.config.QueryIdempotencyProperties;
import com.doc.docquery.vo.AnswerResponseVO;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Bounded request ownership and one serial writer per SSE connection. */
@Component
public final class AnswerStreamRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AnswerStreamRunner.class);
    private final AnswerProperties properties;
    private final QueryIdempotencyProperties idempotency;
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ExecutorService writers;
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2, threads("answer-timer"));
    private final java.util.Set<Channel> active = ConcurrentHashMap.newKeySet();

    public AnswerStreamRunner(AnswerProperties properties, QueryIdempotencyProperties idempotency) {
        this.properties = properties;
        this.idempotency = idempotency;
        int size = properties.getStreamConcurrency();
        if (size < 1 || properties.getStreamQueueCapacity() < 2 || properties.getStreamMaxOutputBytes() < 1)
            throw new IllegalArgumentException("Invalid answer stream limits");
        slots = new Semaphore(size);
        workers = pool(size, "answer-worker");
        writers = pool(size, "answer-writer");
    }
    private static ExecutorService pool(int size, String name) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }
    private static ThreadFactory threads(String prefix) {
        AtomicInteger id = new AtomicInteger();
        return task -> { Thread t = new Thread(task, prefix + "-" + id.incrementAndGet()); t.setDaemon(true); return t; };
    }
    public SseEmitter open(String requestId, Supplier<PreparedAnswer> prepare) {
        PreparedAnswer prepared = prepare.get();
        if (!slots.tryAcquire()) {
            var busy = new AnswerStreamException("ANSWER_STREAM_BUSY", "回答服务繁忙，请稍后重试", false);
            try { prepared.reject(busy); } catch (RuntimeException cleanupFailure) { busy.addSuppressed(cleanupFailure); }
            throw busy;
        }
        Channel channel = new Channel(requestId, prepared);
        active.add(channel);
        boolean writerAdmitted = false;
        try {
            writers.execute(channel::write);
            writerAdmitted = true;
            workers.execute(channel::run);
        } catch (RejectedExecutionException failure) {
            channel.closed.set(true);
            channel.control.cancel(AnswerStreamException.cancelled());
            var busy = new AnswerStreamException("ANSWER_STREAM_BUSY", "回答服务繁忙，请稍后重试", false);
            try { prepared.reject(busy); }
            catch (RuntimeException cleanupFailure) { busy.addSuppressed(cleanupFailure); }
            finally {
                channel.workerDone.set(true);
                if (!writerAdmitted) channel.writerDone.set(true);
                channel.release();
            }
            throw busy;
        }
        return channel.emitter;
    }
    public record StreamError(String code, String message, boolean retryable, boolean clearPreview) {}
    public static StreamError error(RuntimeException failure) {
        if (failure instanceof AnswerStreamException e)
            return new StreamError(e.code(), e.getMessage(), !"ANSWER_STREAM_UNSUPPORTED".equals(e.code()), e.clearPreview());
        var classified = QueryAuditFailureClassifier.classify(failure);
        return new StreamError(classified.code(),
                failure instanceof com.doc.docquery.service.AnswerException e ? e.publicMessage() : "回答未能完成，请重试",
                !"ANSWER_MODEL_REQUEST_INVALID".equals(classified.code()),
                "ANSWER_OUTPUT_INVALID".equals(classified.code()) || "ANSWER_MODEL_REQUEST_INVALID".equals(classified.code()));
    }
    private record Event(String name, Object data, boolean terminal) {}
    private final class Channel {
        private final String requestId;
        private final PreparedAnswer prepared;
        private final SseEmitter emitter = new SseEmitter(properties.getTotalTimeout().toMillis() + 5000);
        private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(properties.getStreamQueueCapacity());
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean workerDone = new AtomicBoolean();
        private final AtomicBoolean writerDone = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AnswerExecution control;
        private boolean terminal;
        private long seq;
        private final long started = System.nanoTime();
        private volatile long generating, firstDelta, cancelled;

        Channel(String requestId, PreparedAnswer prepared) {
            this.requestId = requestId;
            this.prepared = prepared;
            control = new AnswerExecution(true, stage -> {
                if ("generating".equals(stage)) generating = System.nanoTime();
                emit(new Event("progress", Map.of("stage", stage), false));
            }, text -> {
                if (firstDelta == 0) firstDelta = System.nanoTime();
                // Bound each queue entry even if a provider sends a large partial response.
                for (int i = 0; i < text.length();) {
                    int end = Math.min(i + 2048, text.length());
                    if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                    emit(new Event("delta", Map.of("seq", ++seq, "text", text.substring(i, end)), false));
                    i = end;
                }
            });
            emitter.onCompletion(this::disconnect);
            emitter.onError(e -> disconnect());
            emitter.onTimeout(this::expire);
        }
        private void expire() {
            var timeout = new AnswerStreamException("ANSWER_EXECUTION_TIMEOUT", "回答超时，请重试", false);
            // Cancellation and commit share one lock. A timeout that loses to commit must
            // not overwrite the committed result with an error event.
            control.cancel(timeout);
            if (!control.committed()) fail(timeout);
        }
        private void disconnect() {
            closed.set(true);
            if (!control.committed()) {
                cancelled = System.nanoTime();
                control.cancel(AnswerStreamException.cancelled());
            }
        }
        private synchronized void emit(Event event) {
            if (closed.get()) throw AnswerStreamException.cancelled();
            if (terminal) return;
            if (!queue.offer(event)) {
                AnswerStreamException failure = new AnswerStreamException("ANSWER_STREAM_BACKPRESSURE", "连接读取过慢，回答已中断", false);
                fail(failure);
                throw failure;
            }
            if (event.terminal()) terminal = true;
        }
        private synchronized void fail(RuntimeException failure) {
            if (terminal || closed.get()) return;
            cancelled = System.nanoTime();
            control.cancel(failure);
            terminal = true;
            queue.clear();
            queue.offer(new Event("error", error(failure), true));
        }
        private void run() {
            ScheduledFuture<?> heartbeat = null, deadline = null, renewal = null;
            try {
                emit(new Event("start", Map.of("version", 1, "requestId", requestId), false));
                heartbeat = timers.scheduleAtFixedRate(() -> {
                    try { emit(new Event("", null, false)); } catch (RuntimeException ignored) { }
                }, 15, 15, TimeUnit.SECONDS);
                deadline = timers.schedule(this::expire, properties.getTotalTimeout().toMillis(), TimeUnit.MILLISECONDS);
                long renewEvery = Math.max(1, idempotency.getRunningTtl().toMillis() / 3);
                renewal = timers.scheduleAtFixedRate(() -> {
                    try { prepared.renew(); } catch (RuntimeException failure) { fail(failure); }
                }, renewEvery, renewEvery, TimeUnit.MILLISECONDS);
                AnswerResponseVO result = prepared.execute(control);
                emit(new Event("done", Map.of("result", result, "replayed", prepared.replayed()), true));
            } catch (RuntimeException failure) {
                // Execute owns audit/lease cleanup. If delivery failed before execute, abandon it.
                fail(failure);
            } finally {
                try { if (!control.committed()) prepared.abandon(); }
                catch (RuntimeException cleanupFailure) { LOG.warn("docquery_stream_cleanup_failed requestId={}", requestId); }
                if (heartbeat != null) heartbeat.cancel(false);
                if (deadline != null) deadline.cancel(false);
                if (renewal != null) renewal.cancel(false);
                control.clearHooks();
                workerDone.set(true);
                release();
                long ended = System.nanoTime();
                LOG.info("docquery_answer_stream requestId={} retrievalMs={} firstDeltaMs={} totalMs={} cancelExitMs={}",
                        requestId, elapsed(generating), elapsed(firstDelta), elapsed(ended),
                        cancelled == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(ended - cancelled));
            }
        }
        private long elapsed(long point) { return point == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(point - started); }
        private void write() {
            try {
                while (!closed.get()) {
                    Event event = queue.poll(1, TimeUnit.SECONDS);
                    if (event == null) continue;
                    if (event.name().isEmpty()) emitter.send(SseEmitter.event().comment("heartbeat"));
                    else emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
                    if (event.terminal()) { emitter.complete(); break; }
                }
            } catch (IOException | RuntimeException failure) {
                disconnect();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                disconnect();
            } finally {
                closed.set(true);
                queue.clear();
                writerDone.set(true);
                release();
            }
        }
        private void release() {
            if (workerDone.get() && writerDone.get() && released.compareAndSet(false, true)) {
                active.remove(this);
                slots.release();
            }
        }
    }
    @PreDestroy
    public void close() {
        active.forEach(Channel::disconnect);
        workers.shutdownNow();
        writers.shutdownNow();
        timers.shutdownNow();
    }
}
