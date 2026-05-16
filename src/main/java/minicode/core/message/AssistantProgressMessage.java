package minicode.core.message;

import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

public record AssistantProgressMessage(String content, Optional<ProviderUsage> providerUsage,
                                       UsageStaleness usageStaleness) implements ChatMessage {
    public AssistantProgressMessage {
        content = Objects.requireNonNull(content, "content");
        providerUsage = Objects.requireNonNull(providerUsage, "providerUsage");
        usageStaleness = Objects.requireNonNull(usageStaleness, "usageStaleness");
    }

    public AssistantProgressMessage(String content) {
        this(content, Optional.empty(), UsageStaleness.fresh());
    }
}
