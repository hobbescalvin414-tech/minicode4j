package minicode.core.message;

public sealed interface ChatMessage permits SystemMessage, UserMessage, AssistantThinkingMessage,
        AssistantMessage, AssistantProgressMessage, AssistantToolCallMessage, ToolResultMessage,
        ContextSummaryMessage {
}
