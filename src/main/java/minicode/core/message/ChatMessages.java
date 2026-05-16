package minicode.core.message;

import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

public final class ChatMessages {
    private ChatMessages() {
    }

    public static ChatMessage withProviderUsage(ChatMessage message, ProviderUsage usage) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(usage, "usage");
        return switch (message) {
            case AssistantMessage m -> new AssistantMessage(m.content(), Optional.of(usage), m.usageStaleness());
            case AssistantProgressMessage m -> new AssistantProgressMessage(m.content(), Optional.of(usage), m.usageStaleness());
            case AssistantToolCallMessage m -> new AssistantToolCallMessage(m.toolUseId(), m.toolName(), m.input(), Optional.of(usage), m.usageStaleness());
            default -> message;
        };
    }

    public static ChatMessage markUsageStale(ChatMessage message, String reason) {
        Objects.requireNonNull(message, "message");
        UsageStaleness stale = UsageStaleness.stale(reason);
        return switch (message) {
            case AssistantMessage m -> new AssistantMessage(m.content(), m.providerUsage(), stale);
            case AssistantProgressMessage m -> new AssistantProgressMessage(m.content(), m.providerUsage(), stale);
            case AssistantToolCallMessage m -> new AssistantToolCallMessage(m.toolUseId(), m.toolName(), m.input(), m.providerUsage(), stale);
            default -> message;
        };
    }
}
