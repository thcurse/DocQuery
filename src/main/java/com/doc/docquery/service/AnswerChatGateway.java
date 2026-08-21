package com.doc.docquery.service;

import java.util.List;

/** LangChain4j 之外的稳定 Answer Chat/Tool Calling 端口。 */
public interface AnswerChatGateway {

    Turn chat(List<Message> messages, boolean toolsEnabled);

    sealed interface Message permits SystemPrompt, UserContent, AssistantContent,
            ToolResultContent {
    }

    record SystemPrompt(String text) implements Message {
    }

    record UserContent(String text) implements Message {
    }

    record AssistantContent(String text, List<ToolCall> toolCalls) implements Message {
        public AssistantContent {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    record ToolResultContent(
            String callId,
            String toolName,
            String resultJson
    ) implements Message {
    }

    record ToolCall(String id, String name, String argumentsJson) {
    }

    record Turn(String text, List<ToolCall> toolCalls) {
        public Turn {
            toolCalls = List.copyOf(toolCalls);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }
}
