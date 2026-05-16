package minicode.context.compact;

import java.time.Instant;
import java.util.Objects;

public record CompactMetadata(CompactTrigger trigger, long tokensBefore, long tokensAfter,
                              int messagesCompressed, Instant timestamp) {
    public CompactMetadata {
        trigger = Objects.requireNonNull(trigger, "trigger");
        if (tokensBefore < 0 || tokensAfter < 0 || messagesCompressed < 0) {
            throw new IllegalArgumentException("compact counts must be non-negative");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
