package com.doc.docquery.stream;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AnswerExecutionTest {
    @Test void cancelledExecutionCannotCommitAndLateHandlesAreCancelled() {
        AnswerExecution execution = new AnswerExecution(true, s -> {}, s -> {});
        execution.begin(Duration.ofSeconds(1));
        execution.cancel(AnswerStreamException.cancelled());
        AtomicInteger hooks = new AtomicInteger();
        execution.onCancel(hooks::incrementAndGet);
        assertThat(hooks).hasValue(1);
        assertThatThrownBy(() -> execution.commit(() -> fail("must not commit"))).isInstanceOf(AnswerStreamException.class);
    }
    @Test void committedResultSurvivesDisconnect() {
        AnswerExecution execution = new AnswerExecution(true, s -> {}, s -> {});
        execution.begin(Duration.ofSeconds(1));
        execution.commit(() -> {});
        execution.cancel(AnswerStreamException.cancelled());
        execution.check();
        assertThat(execution.committed()).isTrue();
    }
}
