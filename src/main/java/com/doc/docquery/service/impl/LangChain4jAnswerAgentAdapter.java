package com.doc.docquery.service.impl;

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

    public LangChain4jAnswerAgentAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public AgentRun start(Request request, ToolHandler tools, Observer observer) {
        int maxToolRoundTrips = Math.max(1, request.maxToolRoundTrips());
        ChatModel observed = new ObservedChatModel(
                chatModel,
                observer,
                maxToolRoundTrips
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
                .hallucinatedToolNameStrategy(call -> ToolExecutionResultMessage.from(
                        call.id(),
                        call.name(),
                        "{\"status\":\"INVALID_ARGUMENT\",\"message\":\"Unknown tool\"}"
                ))
                .build();
        return userMessage -> invoke(agent, userMessage, submission);
    }

    @Override
    public String finalizeAnswer(FinalizationRequest request, Observer observer) {
        observer.beforeModelCall();
        ChatResponse response;
        try {
            response = chatModel.chat(List.of(
                    SystemMessage.from(request.systemPrompt()),
                    UserMessage.from(request.userPayload())
            ));
        } catch (RuntimeException exception) {
            LOG.error(
                    "docquery_answer_finalizer_provider_failure provider={} client={} "
                            + "exceptionType={}",
                    chatModel.provider(),
                    chatModel.getClass().getName(),
                    exception.getClass().getName(),
                    exception
            );
            throw unavailable(exception);
        }
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
        return new AnswerException(
                MODEL_UNAVAILABLE,
                "Answer model is unavailable",
                cause
        );
    }

    private interface Agent {
        String answer(String userMessage);
    }

    private static final class SubmissionCapture {
        private String json;
    }

    private static final class ObservedChatModel implements ChatModel {
        private final ChatModel delegate;
        private final Observer observer;
        private final int normalToolRoundTripLimit;
        private int completedToolRoundTrips;

        private ObservedChatModel(
                ChatModel delegate,
                Observer observer,
                int maxToolRoundTrips
        ) {
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
            observer.beforeModelCall();
            ChatResponse response;
            try {
                response = delegate.chat(request);
            } catch (RuntimeException exception) {
                LOG.error(
                        "docquery_answer_provider_failure provider={} client={} exceptionType={}",
                        delegate.provider(),
                        delegate.getClass().getName(),
                        exception.getClass().getName(),
                        exception
                );
                throw exception;
            }
            if (response == null || response.aiMessage() == null) {
                throw new AnswerException(MODEL_UNAVAILABLE, "Answer model is unavailable");
            }
            int calls = response.aiMessage().toolExecutionRequests().size();
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
