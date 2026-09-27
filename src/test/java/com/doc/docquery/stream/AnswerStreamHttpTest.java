package com.doc.docquery.stream;

import com.doc.docquery.config.AnswerProperties;
import com.doc.docquery.config.QueryIdempotencyProperties;
import com.doc.docquery.controller.AnswerController;
import com.doc.docquery.exception.BusinessExceptionHandler;
import com.doc.docquery.security.QueryAccessException;
import com.doc.docquery.security.QueryRequestIdFilter;
import com.doc.docquery.service.AuditedQueryService;
import com.doc.docquery.vo.AnswerResponseVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnswerStreamHttpTest {
    final AuditedQueryService service = mock(AuditedQueryService.class);
    final AnswerProperties properties = new AnswerProperties();
    final AnswerStreamRunner runner = new AnswerStreamRunner(properties, new QueryIdempotencyProperties());
    final String path = "/api/v1/service/knowledge-bases/1/answer/stream";
    MockMvc mvc() {
        AnswerController controller = new AnswerController(service, runner);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BusinessExceptionHandler()).addFilter(new QueryRequestIdFilter()).build();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.doc.docquery.service.AnswerException.Detail.class)
    void invalidOutputIs503ForOrdinaryAndSingleClearingErrorForSse(com.doc.docquery.service.AnswerException.Detail detail) throws Exception {
        var failure = com.doc.docquery.service.AnswerException.invalidOutput(detail);
        when(service.answer(anyString(), any(), any(), any(), any(), anyLong(), any())).thenThrow(failure);
        MockMvc mvc = mvc();
        mvc.perform(post(path.replace("/stream", "")).contentType("application/json").content("{\"query\":\"test\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ANSWER_OUTPUT_INVALID"))
                .andExpect(jsonPath("$.message").value(failure.publicMessage()));
        when(service.prepareAnswer(anyString(), any(), any(), any(), any(), anyLong(), any())).thenReturn(new PreparedAnswer() {
            public boolean replayed(){return false;} public void renew(){} public void abandon(){}
            public AnswerResponseVO execute(AnswerExecution execution){execution.delta("unverified");throw failure;}
        });
        var response = mvc.perform(post(path).contentType("application/json").content("{\"query\":\"test\"}")).andReturn();
        response.getAsyncResult(3000);
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk());
        assertThat(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .containsOnlyOnce("event:error").contains("ANSWER_OUTPUT_INVALID", "\"clearPreview\":true", failure.publicMessage())
                .doesNotContain("event:done");
    }

    @Test void budgetExhaustionIsFailureForBothTransportsNotInsufficientEvidence() throws Exception {
        var failure = new com.doc.docquery.service.AnswerException(
                com.doc.docquery.service.AnswerException.Reason.EXECUTION_LIMIT_EXCEEDED,"budget exhausted");
        when(service.answer(anyString(),any(),any(),any(),any(),anyLong(),any())).thenThrow(failure);
        MockMvc mvc = mvc();
        mvc.perform(post(path.replace("/stream", "")).contentType("application/json").content("{\"query\":\"test\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANSWER_EXECUTION_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(failure.publicMessage()));
        when(service.prepareAnswer(anyString(),any(),any(),any(),any(),anyLong(),any())).thenReturn(new PreparedAnswer() {
            public boolean replayed(){return false;} public void renew(){} public void abandon(){}
            public AnswerResponseVO execute(AnswerExecution execution){throw failure;}
        });
        var response=mvc.perform(post(path).contentType("application/json").content("{\"query\":\"test\"}")).andReturn();
        response.getAsyncResult(3000);
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk());
        assertThat(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .containsOnlyOnce("event:error").contains("ANSWER_EXECUTION_LIMIT_EXCEEDED",failure.publicMessage())
                .doesNotContain("event:done","INSUFFICIENT_EVIDENCE");
    }

    @AfterEach void close() { runner.close(); }
    PreparedAnswer prepared(CountDownLatch finish) { return prepared(finish, "这是逐段回答"); }
    PreparedAnswer prepared(CountDownLatch finish, String text) {
        return new PreparedAnswer() {
            public boolean replayed() { return false; }
            public void renew() {}
            public void abandon() {}
            public AnswerResponseVO execute(AnswerExecution control) {
                control.progress("generating");
                control.delta(text);
                try { if (!finish.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw AnswerStreamException.cancelled(); }
                control.commit(() -> {});
                return new AnswerResponseVO("exec", 1L, "ANSWERED", "这是完整回答", "KEYWORD", "KEYWORD", false, null, List.of());
            }
        };
    }
    @Test void flushesDeltaBeforeCompletionAndEmitsOneDone() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        when(service.prepareAnswer(anyString(), any(), any(), any(), any(), anyLong(), any())).thenReturn(prepared(finish));
        MockMvc mvc = mvc();
        MvcResult result = mvc.perform(post(path).contentType("application/json").accept("text/event-stream")
                .content("{\"query\":\"test\"}")).andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().exists("X-DocQuery-Request-Id")).andReturn();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!result.getResponse().getContentAsString().contains("event:delta") && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(result.getResponse().getContentAsString()).contains("event:delta").doesNotContain("event:done");
        finish.countDown();
        result.getAsyncResult(3000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).containsOnlyOnce("event:done");
    }
    @Test void preservesEmojiAtBoundedEventBoundary() throws Exception {
        String text = "中".repeat(2047) + "😀" + "文";
        when(service.prepareAnswer(anyString(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(prepared(new CountDownLatch(0), text));
        MockMvc mvc = mvc();
        MvcResult result = mvc.perform(post(path).contentType("application/json").accept("text/event-stream")
                .content("{\"query\":\"test\"}")).andReturn();
        result.getAsyncResult(3000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        StringBuilder received = new StringBuilder();
        var mapper = new tools.jackson.databind.ObjectMapper();
        for (String line : result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("data:")) {
                var data = mapper.readTree(line.substring(5));
                if (data.has("text")) received.append(data.get("text").asText());
            }
        }
        assertThat(received.toString()).isEqualTo(text);
    }

    @Test void timeoutEndsStreamWithErrorInsteadOfDone() throws Exception {
        properties.setTotalTimeout(java.time.Duration.ofMillis(100));
        when(service.prepareAnswer(anyString(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new PreparedAnswer() {
                    public boolean replayed() { return false; }
                    public void renew() {}
                    public void abandon() {}
                    public AnswerResponseVO execute(AnswerExecution control) {
                        for (;;) {
                            control.check();
                            try { Thread.sleep(5); } catch (InterruptedException e) { throw AnswerStreamException.cancelled(); }
                        }
                    }
                });
        MockMvc mvc = mvc();
        MvcResult result = mvc.perform(post(path).contentType("application/json").accept("text/event-stream")
                .content("{\"query\":\"test\"}")).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(3000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).containsOnlyOnce("event:error")
                .contains("ANSWER_EXECUTION_TIMEOUT").doesNotContain("event:done");
    }

    @Test void timeoutThatLosesToCommitDoesNotReplaceDoneWithError() throws Exception {
        properties.setTotalTimeout(java.time.Duration.ofMillis(80));
        var committing = new CountDownLatch(1);
        var finishCommit = new CountDownLatch(1);
        when(service.prepareAnswer(anyString(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new PreparedAnswer() {
                    public boolean replayed() { return false; }
                    public void renew() {}
                    public void abandon() {}
                    public AnswerResponseVO execute(AnswerExecution control) {
                        control.commit(() -> {
                            committing.countDown();
                            try { finishCommit.await(2, TimeUnit.SECONDS); }
                            catch (InterruptedException failure) { throw new RuntimeException(failure); }
                        });
                        return new AnswerResponseVO("exec", 1L, "ANSWERED", "回答", "KEYWORD", "KEYWORD", false, null, List.of());
                    }
                });
        MockMvc mvc = mvc();
        MvcResult result = mvc.perform(post(path).contentType("application/json").accept("text/event-stream")
                .content("{\"query\":\"test\"}")).andReturn();
        assertThat(committing.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(160);
        finishCommit.countDown();
        result.getAsyncResult(3000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).containsOnlyOnce("event:done").doesNotContain("event:error");
    }

    @Test void authenticationFailureIsJson401BeforeStreamStarts() throws Exception {
        when(service.prepareAnswer(anyString(), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new QueryAccessException(QueryAccessException.Reason.APPLICATION_CREDENTIAL_INVALID, "invalid"));
        mvc().perform(post(path).contentType("application/json").accept("text/event-stream").content("{\"query\":\"test\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("APPLICATION_CREDENTIAL_INVALID"));
    }
    @Test void invalidJsonUsesAnswerErrorAndCarriesRequestId() throws Exception {
        mvc().perform(post(path).contentType("application/json").accept("text/event-stream").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ANSWER_REQUEST"))
                .andExpect(header().exists("X-DocQuery-Request-Id"));
    }
}
