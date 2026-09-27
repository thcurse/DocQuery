package com.doc.docquery.stream;
import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.impl.LangChain4jAnswerAgentAdapter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnswerStreamCancellationTest {
    @Test void cancellationBeforeFirstEventWaitsForTransportAndCancelsLateHandle() throws Exception {
        var model = mock(StreamingChatModel.class);
        var callbacks = new AtomicReference<StreamingChatResponseHandler>();
        var called = new CountDownLatch(1);
        doAnswer(invocation -> { callbacks.set(invocation.getArgument(1)); called.countDown(); return null; })
                .when(model).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(StreamingChatResponseHandler.class));
        var adapter = new LangChain4jAnswerAgentAdapter(mock(ChatModel.class), duration -> model, null);
        var execution = new AnswerExecution(true, stage -> {}, text -> fail("late text must be ignored"));
        execution.begin(Duration.ofSeconds(3));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> adapter.streamFinalAnswer(
                    new AnswerAgentGateway.FinalizationRequest("system","question","{}"),
                    new AnswerAgentGateway.Observer() {
                        public void beforeModelCall() {}
                        public void toolRound(int count) {}
                        public void afterToolCall() {}
                    }, execution, 1024));
            assertThat(called.await(1,TimeUnit.SECONDS)).isTrue();
            execution.cancel(AnswerStreamException.cancelled());
            assertThatThrownBy(() -> future.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            StreamingHandle handle = mock(StreamingHandle.class);
            callbacks.get().onPartialResponse(new PartialResponse("{\"answer\":\"late"), new PartialResponseContext(handle));
            assertThatThrownBy(() -> future.get(1,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AnswerStreamException.class);
            verify(handle, atLeastOnce()).cancel();
        } finally { pool.shutdownNow(); }
    }
}
