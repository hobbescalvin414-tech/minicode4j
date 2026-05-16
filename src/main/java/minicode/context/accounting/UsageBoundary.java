package minicode.context.accounting;

import java.util.Objects;
import java.util.Optional;

public record UsageBoundary(int messageIndex, Optional<String> messageId) {
    public UsageBoundary {
        if (messageIndex < 0) {
            throw new IllegalArgumentException("messageIndex must be non-negative");
        }
        messageId = Objects.requireNonNull(messageId, "messageId");
        messageId.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("messageId must not be blank when present");
            }
        });
    }
}
