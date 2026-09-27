package com.doc.docquery.stream;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Coordinates cancellation and result commitment; no HTTP dependencies. */
public final class AnswerExecution {
    private final boolean streaming;
    private final Consumer<String> progress;
    private final Consumer<String> delta;
    private final CopyOnWriteArrayList<Runnable> hooks = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;
    private volatile boolean committed;
    private volatile long deadline = Long.MAX_VALUE;
    public AnswerExecution(boolean streaming, Consumer<String> progress, Consumer<String> delta) {
        this.streaming = streaming;
        this.progress = progress;
        this.delta = delta;
    }
    public static AnswerExecution ordinary() { return new AnswerExecution(false, s -> {}, s -> {}); }
    public boolean streaming() { return streaming; }
    public boolean committed() { return committed; }
    public void begin(Duration budget) { deadline = System.nanoTime() + budget.toNanos(); }
    public Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }
    public void check() {
        if (failure != null) throw failure;
        if (!committed && System.nanoTime() >= deadline) {
            cancel(streaming ? new AnswerStreamException("ANSWER_EXECUTION_TIMEOUT", "回答超时，请重试", false)
                    : new com.doc.docquery.service.AnswerException(
                            com.doc.docquery.service.AnswerException.Reason.EXECUTION_TIMEOUT, "Answer execution timed out"));
            if (failure != null) throw failure;
        }
    }
    public void progress(String stage) { check(); progress.accept(stage); }
    public void delta(String text) { check(); if (!text.isEmpty()) delta.accept(text); }
    public void onCancel(Runnable hook) {
        hooks.add(hook);
        if (failure != null) hook.run();
    }
    public void cancel(RuntimeException cause) {
        synchronized (this) {
            if (committed || failure != null) return;
            failure = cause;
        }
        for (Runnable hook : hooks) {
            try { hook.run(); } catch (RuntimeException ignored) { /* best effort transport close */ }
        }
    }
    public synchronized void commit(Runnable action) {
        check();
        action.run();
        committed = true;
    }
    public void clearHooks() { hooks.clear(); }
}
