package minicode.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

public record AssistantToolCallMessage(String toolUseId, String toolName, JsonNode input,
                                       Optional<ProviderUsage> providerUsage,
                                       UsageStaleness usageStaleness) implements ChatMessage {
    public AssistantToolCallMessage {
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        input = Objects.requireNonNull(input, "input");
        providerUsage = Objects.requireNonNull(providerUsage, "providerUsage");
        usageStaleness = Objects.requireNonNull(usageStaleness, "usageStaleness");
    }

    public AssistantToolCallMessage(String toolUseId, String toolName, JsonNode input) {
        this(toolUseId, toolName, input, Optional.empty(), UsageStaleness.fresh());
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
