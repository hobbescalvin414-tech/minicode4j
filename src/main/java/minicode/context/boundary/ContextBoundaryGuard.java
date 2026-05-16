package minicode.context.boundary;

import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ContextBoundaryGuard {
    private ContextBoundaryGuard() {
    }

    public static boolean isCompactSafeBoundary(List<ChatMessage> messages) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (actualMessages.isEmpty()) {
            return true;
        }
        Set<String> openToolCalls = new HashSet<>();
        Set<String> completedToolCalls = new HashSet<>();
        for (ChatMessage message : actualMessages) {
            if (message instanceof AssistantToolCallMessage toolCall) {
                if (openToolCalls.contains(toolCall.toolUseId()) || completedToolCalls.contains(toolCall.toolUseId())) {
                    return false;
                }
                openToolCalls.add(toolCall.toolUseId());
            } else if (message instanceof ToolResultMessage toolResult) {
                if (!openToolCalls.remove(toolResult.toolUseId())) {
                    return false;
                }
                if (!completedToolCalls.add(toolResult.toolUseId())) {
                    return false;
                }
            }
        }
        return openToolCalls.isEmpty();
    }
}
