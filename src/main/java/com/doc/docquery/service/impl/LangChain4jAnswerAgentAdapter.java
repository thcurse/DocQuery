package com.doc.docquery.service.impl;

import com.doc.docquery.stream.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.doc.docquery.service.AnswerAgentGateway;
import com.doc.docquery.service.AnswerException;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.doc.docquery.service.AnswerException.Reason.EXECUTION_LIMIT_EXCEEDED;
import static com.doc.docquery.service.AnswerException.Reason.MODEL_UNAVAILABLE;
import static com.doc.docquery.service.AnswerException.Reason.OUTPUT_INVALID;

/** 生产高层 Agent：只负责 LangChain4j 工具循环，不持有任何业务授权上下文。 */
public class LangChain4jAnswerAgentAdapter implements AnswerAgentGateway {

    private static final Logger LOG = LoggerFactory.getLogger(
            LangChain4jAnswerAgentAdapter.class
    );
    private static final String FINALIZATION_INSTRUCTION = """
            Stop using information tools and finish retrieval now. Call submit_evidence exactly
            once with the smallest relation-complete handoff useful for answering the user's
            complete question. Put supporting citation anchors in evidenceIds. Put any R# page or
            context packages whose labels, rows, columns, periods, or surrounding text must stay
            together in readRefs. This is an evidence handoff, not the final answer. Use two empty
            lists only when no retrieved evidence is relevant. Do not emit ordinary text.
            """;
    private static final ToolSpecification SEARCH = tool(
            "search",
            "Search canonical evidence in the authorized active-version snapshot. Omit "
                    + "documentRef to search the current knowledge base, or use a documentRef "
                    + "returned by search to narrow the search. Results are ranked section "
                    + "candidates with direct canonical evidence anchors; search does not expand "
                    + "surrounding context. Open S# to inspect a section, or open E# when page or "
                    + "nearby context is needed. When nextCursor is returned, pass it back with "
                    + "the same query and documentRef to read the next result page. Reformulate "
                    + "the query when all pages for the current search do not resolve the question. "
                    + "Start with the core retrieval subject. If coverage remains concentrated "
                    + "and the question is incomplete, target a materially missing aspect next; "
                    + "do not merely paraphrase an earlier query.",
            JsonObjectSchema.builder()
                    .addStringProperty("query", "Required search query, at most 2000 characters")
                    .addStringProperty(
                            "documentRef",
                            "Optional opaque D# document reference returned by search"
                    )
                    .addStringProperty(
                            "cursor",
                            "Optional opaque C# cursor returned by the preceding identical search"
                    )
                    .required("query")
                    .additionalProperties(false)
                    .build()
    );
    private static final ToolSpecification SUBMIT_EVIDENCE = tool(
            "submit_evidence",
            "Finish retrieval and hand selected evidence to an isolated final answer pass. Call "
                    + "this only after search/open has resolved the question or available search "
                    + "paths are exhausted. evidenceIds contains supporting E# citation anchors. "
                    + "readRefs contains R# page/context packages that must remain together to "
                    + "preserve labels, table relations, periods, or surrounding text. Use the "
                    + "smallest relation-complete handoff. Both may be empty only when no retrieved "
                    + "evidence is relevant. Do not answer the user's question in this tool.",
            JsonObjectSchema.builder()
                    .addProperty(
                            "evidenceIds",
                            JsonArraySchema.builder()
                                    .description("Selected registered E# references in relevance order")
                                     .items(JsonStringSchema.builder().build())
                                     .build()
                    )
                    .addProperty(
                            "readRefs",
                            JsonArraySchema.builder()
                                    .description("Selected opaque R# relation-complete read packages")
                                    .items(JsonStringSchema.builder().build())
                                    .build()
                    )
                    .required("evidenceIds", "readRefs")
                    .additionalProperties(false)
                    .build()
    );
    private static final ToolSpecification OPEN = tool(
            "open",
            "Open an opaque reference returned by search or open. Opening D# returns the real "
                    + "canonical outline. Opening S# reads the smallest authorized canonical "
                    + "section and may return child section references. Opening E# expands the "
                    + "registered evidence to its authorized canonical page or nearby context "
                    + "and returns its current readRef plus possible previousRef/nextRef R# "
                    + "packages. Opening R# reads that canonical page or window. Cite E# values, "
                    + "and submit an R# when its complete context must survive final handoff.",
            JsonObjectSchema.builder()
                    .addStringProperty("ref", "Required opaque D#, S#, E#, or R# reference")
                    .required("ref")
                    .additionalProperties(false)
                    .build()
    );

    private final ChatModel chatModel;
    private final Function<Duration, StreamingChatModel> streamingFactory;
    private final Function<Duration, ChatModel> chatFactory;
    private final ResponseFormat finalFormat;
    private final String identity;
    private final String modelLabel;

    @Override public String configurationIdentity() { return identity; }

    public LangChain4jAnswerAgentAdapter(ChatModel chatModel) {
        this(chatModel, null, null);
    }

    public LangChain4jAnswerAgentAdapter(ChatModel chatModel,
            Function<Duration, StreamingChatModel> streamingFactory,
            Function<Duration, ChatModel> chatFactory) {
        this(chatModel, streamingFactory, chatFactory, ResponseFormat.JSON, "", chatModel.getClass().getSimpleName());
    }

    public LangChain4jAnswerAgentAdapter(ChatModel chatModel,
            Function<Duration, StreamingChatModel> streamingFactory,
            Function<Duration, ChatModel> chatFactory, ResponseFormat finalFormat, String identity, String modelLabel) {
        this.finalFormat = finalFormat;
        this.identity = identity;
        this.modelLabel = modelLabel;
        this.chatModel = chatModel;
        this.streamingFactory = streamingFactory;
        this.chatFactory = chatFactory;
    }

    @Override
    public String streamFinalAnswer(FinalizationRequest request, Observer observer,
            AnswerExecution execution, int maxBytes) {
        if (streamingFactory == null) return AnswerAgentGateway.super.streamFinalAnswer(request, observer, execution, maxBytes);
        observer.beforeModelCall();
        StreamTimings timings = new StreamTimings(observer.queryExecutionId());
        AnswerDeltaParser parser = new AnswerDeltaParser(maxBytes, text -> {
            timings.answer();
            execution.delta(text);
        });
        CompletableFuture<String> completion = new CompletableFuture<>();
        AtomicReference<StreamingHandle> handle = new AtomicReference<>();
        CountDownLatch transportClosed = new CountDownLatch(1);
        long transportDeadline = System.nanoTime() + execution.remaining().toNanos();
        boolean started = false;
        execution.onCancel(() -> {
            StreamingHandle active = handle.get();
            if (active != null) active.cancel();
            completion.completeExceptionally(AnswerStreamException.cancelled());
        });
        try {
            StreamingChatModel model = streamingFactory.apply(execution.remaining());
            model.chat(ChatRequest.builder().messages(
                    SystemMessage.from(request.systemPrompt()), UserMessage.from(request.userPayload()))
                    .responseFormat(finalFormat).build(),
                    new StreamingChatResponseHandler() {
                        @Override public void onPartialResponse(PartialResponse part, PartialResponseContext context) {
                            handle.set(context.streamingHandle());
                            if (completion.isDone()) { context.streamingHandle().cancel(); transportClosed.countDown(); return; }
                            try {
                                execution.check();
                                timings.text();
                                parser.accept(part.text());
                            }
                            catch (RuntimeException failure) {
                                completion.completeExceptionally(failure);
                                context.streamingHandle().cancel();
                                transportClosed.countDown();
                            }
                        }
                        @Override public void onPartialThinking(PartialThinking part, PartialThinkingContext context) {
                            timings.thinking();
                            handle.set(context.streamingHandle());
                            if (completion.isDone()) { context.streamingHandle().cancel(); transportClosed.countDown(); }
                        }
                        @Override public void onCompleteResponse(ChatResponse response) {
                            if (!completion.isDone()) {
                                timings.complete(response);
                                logFinish("stream_final", observer, response);
                                if (response != null && response.finishReason() == FinishReason.LENGTH) {
                                    completion.completeExceptionally(AnswerException.invalidOutput(AnswerException.Detail.TRUNCATED));
                                } else if (parser.raw().isBlank()) completion.completeExceptionally(AnswerException.invalidOutput(AnswerException.Detail.STRUCTURE));
                                else completion.complete(parser.raw());
                            }
                            transportClosed.countDown();
                        }
                        @Override public void onError(Throwable error) {
                            if (!completion.isDone())
                                completion.completeExceptionally(providerFailure(error, "stream_final", modelLabel, observer.queryExecutionId()));
                            transportClosed.countDown();
                        }
                    });
            started = true;
            for (;;) {
                execution.check();
                try { return completion.get(Math.min(200, Math.max(1, execution.remaining().toMillis())), TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { /* observe cancellation and absolute deadline */ }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AnswerStreamException.cancelled();
        } catch (ExecutionException exception) {
            execution.check();
            if (exception.getCause() instanceof RuntimeException failure) throw failure;
            throw new AnswerStreamException("ANSWER_MODEL_UNAVAILABLE", "模型流式请求失败，请重试", false);
        } catch (RuntimeException failure) {
            if (failure instanceof AnswerException || failure instanceof AnswerStreamException) throw failure;
            throw providerFailure(failure, "stream_final", modelLabel, observer.queryExecutionId());
        } finally {
            completion.cancel(false);
            StreamingHandle active = handle.get();
            if (active != null) active.cancel();
            else if (started) {
                // Before the first provider event no cancellation handle exists. Keep ownership
                // until the transport terminates or its bounded request budget expires.
                try { transportClosed.await(Math.max(1, transportDeadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            timings.log();
        }
    }

    /** Only durations and token counts: never log provider text or hidden reasoning. */
    private static final class StreamTimings {
        private final String queryExecutionId;
        private final long started = System.nanoTime();
        private long firstText, firstAnswer, firstThinking;
        private int chunks;
        private boolean completed;
        private Integer inputTokens, outputTokens;
        private StreamTimings(String queryExecutionId) { this.queryExecutionId = queryExecutionId; }
        synchronized void text() { if (firstText == 0) firstText = System.nanoTime(); chunks++; }
        synchronized void answer() { if (firstAnswer == 0) firstAnswer = System.nanoTime(); }
        synchronized void thinking() { if (firstThinking == 0) firstThinking = System.nanoTime(); }
        synchronized void complete(ChatResponse response) {
            completed = true;
            if (response != null && response.tokenUsage() != null) {
                inputTokens = response.tokenUsage().inputTokenCount();
                outputTokens = response.tokenUsage().outputTokenCount();
            }
        }
        private long elapsed(long value) {
            return value == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(value - started);
        }
        synchronized void log() {
            LOG.info("docquery_answer_model_stream queryExecutionId={} firstThinkingMs={} firstModelTextMs={} firstAnswerMs={} modelChunks={} elapsedMs={} completed={} inputTokens={} outputTokens={}",
                    queryExecutionId, elapsed(firstThinking), elapsed(firstText), elapsed(firstAnswer),
                    chunks, elapsed(System.nanoTime()), completed, inputTokens, outputTokens);
        }
    }

    @Override
    public AgentRun start(Request request, ToolHandler tools, Observer observer) {
        int maxToolRoundTrips = Math.max(1, request.maxToolRoundTrips());
        ChatModel observed = new ObservedChatModel(
                chatModel,
                observer,
                maxToolRoundTrips,
                chatFactory
        );
        SubmissionCapture submission = new SubmissionCapture();
        List<AiServiceTool> agentTools = List.of(
                agentTool(SEARCH, executor("search", tools, observer), ReturnBehavior.TO_LLM),
                agentTool(OPEN, executor("open", tools, observer), ReturnBehavior.TO_LLM),
                agentTool(
                        SUBMIT_EVIDENCE,
                        submissionExecutor(observer, submission),
                        ReturnBehavior.IMMEDIATE
                )
        );
        Agent agent = AiServices.builder(Agent.class)
                .chatModel(observed)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(1_000))
                .systemMessage(request.systemPrompt())
                .tools(agentTools)
                .maxToolCallingRoundTrips(maxToolRoundTrips)
                .hallucinatedToolNameStrategy(call -> {
                    observer.afterToolCall();
                    return ToolExecutionResultMessage.from(call.id(), call.name(),
                            "{\"status\":\"INVALID_ARGUMENT\",\"message\":\"Unknown tool; use search, open or submit_evidence\"}");
                })
                .build();
        return userMessage -> invoke(agent, userMessage, submission);
    }

    @Override
    public String finalizeAnswer(FinalizationRequest request, Observer observer) {
        observer.beforeModelCall();
        ChatResponse response;
        try {
            ChatModel finalModel = chatFactory == null ? chatModel : chatFactory.apply(observer.remaining());
            response = finalModel.chat(ChatRequest.builder().messages(
                    SystemMessage.from(request.systemPrompt()), UserMessage.from(request.userPayload()))
                    .responseFormat(finalFormat).build());
        } catch (RuntimeException exception) {
            throw providerFailure(exception, "final", modelLabel, observer.queryExecutionId());
        }
        logFinish("final", observer, response);
        if (response != null && response.finishReason() == FinishReason.LENGTH)
            throw AnswerException.invalidOutput(AnswerException.Detail.TRUNCATED);

        if (response == null || response.aiMessage() == null
                || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank()) {
            throw new AnswerException(OUTPUT_INVALID, "Answer finalizer output is invalid");
        }
        return response.aiMessage().text();
    }

    private String invoke(
            Agent agent,
            String userMessage,
            SubmissionCapture submission
    ) {
        try {
            agent.answer(userMessage);
            // IMMEDIATE 工具调用会直接结束 AiService，本身不保证产生 assistant 文本；
            // 因此以 submit_evidence 的实参作为检索阶段唯一输出。
            if (submission != null
                    && submission.json != null
                    && !submission.json.isBlank()) {
                return submission.json;
            }
            LOG.warn("docquery_answer_missing_evidence_handoff");
            throw new AnswerException(
                    OUTPUT_INVALID,
                    "Answer model did not call submit_evidence"
            );
        } catch (AnswerException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            AnswerException answerFailure = findAnswerException(exception);
            if (answerFailure != null) {
                throw answerFailure;
            }
            if (containsMessage(exception, "tool calling round trips")) {
                throw new AnswerException(
                        EXECUTION_LIMIT_EXCEEDED,
                        "Answer execution limit was exceeded",
                        exception
                );
            }
            throw unavailable(exception);
        }
    }

    private ToolExecutor executor(
            String name,
            ToolHandler tools,
            Observer observer
    ) {
        return (request, ignored) -> {
            try {
                return tools.execute(name, request.arguments());
            } finally {
                observer.afterToolCall();
            }
        };
    }

    private ToolExecutor submissionExecutor(
            Observer observer,
            SubmissionCapture submission
    ) {
        return (request, ignored) -> {
            try {
                submission.json = request.arguments();
                return request.arguments();
            } finally {
                observer.afterToolCall();
            }
        };
    }

    private AiServiceTool agentTool(
            ToolSpecification specification,
            ToolExecutor executor,
            ReturnBehavior returnBehavior
    ) {
        return AiServiceTool.builder()
                .toolSpecification(specification)
                .toolExecutor(executor)
                .returnBehavior(returnBehavior)
                .build();
    }

    private static ToolSpecification tool(
            String name,
            String description,
            JsonObjectSchema parameters
    ) {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .strict(false)
                .build();
    }

    private boolean containsMessage(Throwable failure, String fragment) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AnswerException findAnswerException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AnswerException answerException) {
                return answerException;
            }
            current = current.getCause();
        }
        return null;
    }

    private AnswerException unavailable(Throwable cause) {
        return providerFailure(cause, "agent", modelLabel, null);
    }

    private static void usage(Observer observer, ChatResponse response) {
        if (response != null && response.tokenUsage() != null)
            observer.modelUsage(response.tokenUsage().inputTokenCount(), response.tokenUsage().outputTokenCount());
    }

    private void logFinish(String stage, Observer observer, ChatResponse response) {
        usage(observer, response);
        LOG.info("docquery_answer_provider_finish model={} stage={} queryExecutionId={} finishReason={}",
                modelLabel, stage, observer.queryExecutionId(), response == null ? "EMPTY" : response.finishReason());
    }

    private static AnswerException providerFailure(Throwable failure, String stage, String model, String queryId) {
        Throwable current = failure;
        Integer status = null;
        boolean invalid = false, timeout = false, truncated = false;
        while (current != null) {
            if (current instanceof AnswerException e) return e;
            if (current instanceof dev.langchain4j.exception.HttpException e) status = e.statusCode();
            invalid |= current instanceof dev.langchain4j.exception.InvalidRequestException
                    || current instanceof dev.langchain4j.exception.AuthenticationException
                    || current instanceof dev.langchain4j.exception.UnsupportedFeatureException;
            timeout |= current instanceof dev.langchain4j.exception.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException || current instanceof TimeoutException;
            // Responses providers can deliver incomplete as an SDK error instead of LENGTH.
            String message = current.getMessage();
            truncated |= message != null && (message.contains("max_output_tokens") || message.contains("max_tokens"))
                    && message.contains("incomplete");
            current = current.getCause();
        }
        truncated &= status == null;
        invalid |= status != null && (status == 400 || status == 401 || status == 403 || status == 404 || status == 422);
        LOG.warn("docquery_answer_provider_failure model={} stage={} queryExecutionId={} httpStatus={} category={}",
                model, stage, queryId, status, invalid ? "REQUEST_INVALID" : truncated ? "TRUNCATED" : timeout ? "TIMEOUT" : "UNAVAILABLE");
        if (truncated && !invalid) return AnswerException.invalidOutput(AnswerException.Detail.TRUNCATED);
        return new AnswerException(invalid ? AnswerException.Reason.MODEL_REQUEST_INVALID
                : timeout ? AnswerException.Reason.EXECUTION_TIMEOUT : MODEL_UNAVAILABLE, "Model request failed");
    }

    private interface Agent {
        String answer(String userMessage);
    }

    private static final class SubmissionCapture {
        private String json;
    }

    private static final class ObservedChatModel implements ChatModel {
        private final ChatModel delegate;
        private final Function<Duration, ChatModel> factory;
        private final Observer observer;
        private final int normalToolRoundTripLimit;
        private int completedToolRoundTrips;
        private int modelCalls;

        private ObservedChatModel(
                ChatModel delegate,
                Observer observer,
                int maxToolRoundTrips,
                Function<Duration, ChatModel> factory
        ) {
            this.factory = factory;
            this.delegate = delegate;
            this.observer = observer;
            // Reserve the final tool round for submit_evidence so exhaustion converges instead of
            // surfacing LangChain4j's max-round exception.
            this.normalToolRoundTripLimit = Math.max(0, maxToolRoundTrips - 1);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            if (completedToolRoundTrips >= normalToolRoundTripLimit) {
                return callAndObserve(finalizationRequest(request, null));
            }

            ChatResponse response = callAndObserve(request.toBuilder()
                    .toolChoice(ToolChoice.AUTO)
                    .build());
            if (response.aiMessage().toolExecutionRequests().isEmpty()) {
                LOG.info("docquery_answer_direct_output_finalization");
                return callAndObserve(finalizationRequest(request, response.aiMessage()));
            }
            int calls = response.aiMessage().toolExecutionRequests().size();
            completedToolRoundTrips++;
            observer.toolRound(calls);
            return response;
        }

        private ChatRequest finalizationRequest(
                ChatRequest request,
                AiMessage draft
        ) {
            ToolSpecification submitEvidence = request.toolSpecifications().stream()
                    .filter(tool -> "submit_evidence".equals(tool.name()))
                    .findFirst()
                    .orElseThrow(() -> new AnswerException(
                            OUTPUT_INVALID,
                            "submit_evidence tool is unavailable"
                    ));
            List<ChatMessage> messages = new ArrayList<>(request.messages());
            if (draft != null) {
                messages.add(draft);
            }
            messages.add(UserMessage.from(FINALIZATION_INSTRUCTION));
            return request.toBuilder()
                    .messages(messages)
                    .toolSpecifications(List.of(submitEvidence))
                    .toolChoice(ToolChoice.REQUIRED)
                    .build();
        }

        private ChatResponse callAndObserve(ChatRequest request) {
            // Tool selection uses function calls; JSON mode belongs only to final answers.
            request = request.toBuilder().responseFormat(ResponseFormat.TEXT).build();
            observer.beforeModelCall();
            ChatResponse response;
            long started = System.nanoTime();
            int call = ++modelCalls;
            try {
                response = (factory == null ? delegate : factory.apply(observer.remaining())).chat(request);
            } catch (RuntimeException exception) {
                throw providerFailure(exception, "agent", delegate.getClass().getSimpleName(), observer.queryExecutionId());
            }
            if (response == null || response.aiMessage() == null) {
                throw new AnswerException(MODEL_UNAVAILABLE, "Answer model is unavailable");
            }
            usage(observer, response);
            int calls = response.aiMessage().toolExecutionRequests().size();
            LOG.info("docquery_answer_model_call queryExecutionId={} call={} elapsedMs={} toolCalls={} inputTokens={} outputTokens={}",
                    observer.queryExecutionId(), call, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), calls,
                    response.tokenUsage() == null ? null : response.tokenUsage().inputTokenCount(),
                    response.tokenUsage() == null ? null : response.tokenUsage().outputTokenCount());
            if (request.toolChoice() == ToolChoice.REQUIRED) {
                if (calls != 1
                        || !"submit_evidence".equals(
                                response.aiMessage().toolExecutionRequests().get(0).name()
                        )) {
                    throw new AnswerException(
                            OUTPUT_INVALID,
                            "Answer model did not finalize with submit_evidence"
                    );
                }
                completedToolRoundTrips++;
                observer.toolRound(calls);
            }
            return response;
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return delegate.defaultRequestParameters();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return delegate.listeners();
        }

        @Override
        public ModelProvider provider() {
            return delegate.provider();
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return delegate.supportedCapabilities();
        }
    }
}
