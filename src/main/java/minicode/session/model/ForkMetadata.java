package minicode.session.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ForkMetadata(String sourceSessionId, Optional<String> sourceEventId, String newSessionId,
                           String cwd, Instant timestamp) {
    public ForkMetadata {
        requireText(sourceSessionId, "sourceSessionId");
        sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        requireText(newSessionId, "newSessionId");
        requireText(cwd, "cwd");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
