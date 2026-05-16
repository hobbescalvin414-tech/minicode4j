package minicode.core.message;

import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

public record AssistantMessage(String content, Optional<ProviderUsage> providerUsage,
                               UsageStaleness usageStaleness) implements ChatMessage {
    public AssistantMessage {
        content = Objects.requireNonNull(content, "content");
        providerUsage = Objects.requireNonNull(providerUsage, "providerUsage");
        usageStaleness = Objects.requireNonNull(usageStaleness, "usageStaleness");
    }

    public AssistantMessage(String content) {
        this(content, Optional.empty(), UsageStaleness.fresh());
    }
}
