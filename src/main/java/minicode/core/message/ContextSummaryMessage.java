package minicode.core.message;

import java.time.Instant;
import java.util.Objects;

public record ContextSummaryMessage(String content, int compressedCount, Instant timestamp) implements ChatMessage {
    public ContextSummaryMessage {
        content = Objects.requireNonNull(content, "content");
        if (compressedCount < 0) {
            throw new IllegalArgumentException("compressedCount must be non-negative");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
