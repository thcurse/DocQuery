package com.doc.docquery.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.AnswerException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LangChain4jAnswerAgentAdapterTest {

    @Test
    void usesAutoToolChoiceAndReturnsOnlyCapturedSubmitEvidenceArguments() {
        AtomicReference<ChatRequest> observed = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                observed.set(request);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                .id("submit-1")
                                .name("submit_evidence")
                                .arguments("{\"evidenceIds\":[\"E1\"],\"readRefs\":[\"R1\"]}")
                                .build()))
                        .build();
            }
        };
        AnswerAgentGateway gateway = new LangChain4jAnswerAgentAdapter(model);

        String result = gateway.start(
                new AnswerAgentGateway.Request("system", 3),
                (name, argumentsJson) -> "{}",
                new NoopObserver()
        ).next("question");

        assertThat(result).isEqualTo(
                "{\"evidenceIds\":[\"E1\"],\"readRefs\":[\"R1\"]}"
        );
        assertThat(observed.get().toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(observed.get().toolSpecifications())
                .filteredOn(specification -> specification.name().equals("search"))
                .singleElement()
                .satisfies(specification -> assertThat(specification.description())
                        .contains("core retrieval subject", "materially missing aspect")
                );
    }

    @Test
    void reservesLastToolRoundForSubmitEvidenceHandoff() {
        List<ChatRequest> observed = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                observed.add(request);
                if (modelCalls.getAndIncrement() == 0) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                    .id("search-1")
                                    .name("search")
                                    .arguments("{\"query\":\"missing fact\"}")
                                    .build()))
                            .build();
                }
                return submitEvidence();
            }
        };
        AnswerAgentGateway gateway = new LangChain4jAnswerAgentAdapter(model);

        String result = gateway.start(
                new AnswerAgentGateway.Request("system", 2),
                (name, argumentsJson) -> "{\"status\":\"OK\",\"candidates\":[]}",
                new NoopObserver()
        ).next("question");

        assertThat(result).contains("\"evidenceIds\":[\"E1\"]");
        assertThat(observed).hasSize(2);
        assertThat(observed.get(0).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(observed.get(1).toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(observed.get(1).toolSpecifications())
                .extracting(specification -> specification.name())
                .containsExactly("submit_evidence");
        assertThat(observed.get(1).messages().get(
                observed.get(1).messages().size() - 1
        ).toString()).contains("Stop using information tools and finish retrieval now");
    }

    @Test
    void repairsOrdinaryFinalTextWithSubmitEvidenceOnlyTurn() {
        List<ChatRequest> observed = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                observed.add(request);
                if (modelCalls.getAndIncrement() == 0) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("ordinary final text"))
                            .build();
                }
                return submitEvidence();
            }
        };
        AnswerAgentGateway gateway = new LangChain4jAnswerAgentAdapter(model);

        String result = gateway.start(
                new AnswerAgentGateway.Request("system", 3),
                (name, argumentsJson) -> "{}",
                new NoopObserver()
        ).next("question");

        assertThat(result).contains("\"evidenceIds\":[\"E1\"]");
        assertThat(observed).hasSize(2);
        assertThat(observed.get(0).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(observed.get(1).toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(observed.get(1).toolSpecifications())
                .extracting(specification -> specification.name())
                .containsExactly("submit_evidence");
        assertThat(observed.get(1).messages()).anySatisfy(message ->
                assertThat(message.toString()).contains("ordinary final text")
        );
    }

    @Test
    void regeneratesFinalAnswerFromSelectedEvidenceWithoutTools() {
        AtomicReference<ChatRequest> observed = new AtomicReference<>();
        AtomicInteger observedModelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                observed.set(request);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(
                                "{\"status\":\"ANSWERED\",\"answer\":\"122\","
                                        + "\"evidenceIds\":[\"E1\",\"E2\"]}"
                        ))
                        .build();
            }
        };
        AnswerAgentGateway gateway = new LangChain4jAnswerAgentAdapter(model);
        AnswerAgentGateway.Observer observer = new NoopObserver() {
            @Override
            public void beforeModelCall() {
                observedModelCalls.incrementAndGet();
            }
        };

        String result = gateway.finalizeAnswer(
                new AnswerAgentGateway.FinalizationRequest(
                        "finalizer system",
                        "selected evidence payload",
                        "agent selection"
                ),
                observer
        );

        assertThat(result).contains("\"answer\":\"122\"");
        assertThat(observedModelCalls).hasValue(1);
        assertThat(observed.get().toolSpecifications()).isEmpty();
        assertThat(observed.get().messages()).hasSize(2);
        assertThat(observed.get().messages().get(0).toString())
                .contains("finalizer system");
        assertThat(observed.get().messages().get(1).toString())
                .contains("selected evidence payload");
    }

    @Test
    void logsProviderExceptionBeforeMappingStableServiceFailure() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                LangChain4jAnswerAgentAdapter.class
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ChatModel failing = new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    throw new IllegalStateException(
                            "provider-status=400 unsupported response_format"
                    );
                }
            };
            AnswerAgentGateway gateway = new LangChain4jAnswerAgentAdapter(failing);

            AnswerException failure = catchThrowableOfType(
                    AnswerException.class,
                    () -> gateway.start(
                            new AnswerAgentGateway.Request("system", 3),
                            (name, argumentsJson) -> "{}",
                            new NoopObserver()
                    ).next("question")
            );

            assertThat(failure.reason()).isEqualTo(
                    AnswerException.Reason.MODEL_UNAVAILABLE
            );
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("docquery_answer_provider_failure")
                        .contains("java.lang.IllegalStateException");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage())
                        .contains("provider-status=400 unsupported response_format");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static ChatResponse submitEvidence() {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("submit-1")
                        .name("submit_evidence")
                        .arguments("{\"evidenceIds\":[\"E1\"],\"readRefs\":[]}")
                        .build()))
                .build();
    }

    private static class NoopObserver implements AnswerAgentGateway.Observer {
        @Override
        public void beforeModelCall() {
        }

        @Override
        public void toolRound(int requestedCalls) {
        }

        @Override
        public void afterToolCall() {
        }
    }
}
