package com.doc.docquery.service;

/** 高层单轮 Agent 端口；LangChain4j 管理工具消息循环，DocQuery 提供工具和硬边界。 */
public interface AnswerAgentGateway {

    AgentRun start(Request request, ToolHandler tools, Observer observer);

    /** 用同一 Answer 模型在无 Agent 历史的干净上下文中生成最终答案。 */
    default String finalizeAnswer(FinalizationRequest request, Observer observer) {
        return request.agentSelectionJson();
    }

    record Request(
            String systemPrompt,
            int maxToolRoundTrips
    ) {
    }

    record FinalizationRequest(
            String systemPrompt,
            String userPayload,
            String agentSelectionJson
    ) {
    }

    @FunctionalInterface
    interface AgentRun {
        String next(String userMessage);
    }

    @FunctionalInterface
    interface ToolHandler {
        String execute(String name, String argumentsJson);
    }

    interface Observer {
        void beforeModelCall();

        void toolRound(int requestedCalls);

        void afterToolCall();
    }
}
