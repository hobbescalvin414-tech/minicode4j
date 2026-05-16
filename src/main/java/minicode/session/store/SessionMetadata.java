package minicode.session.store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SessionMetadata(String sessionId, String cwd, Optional<String> title, int eventCount,
                              Instant updatedAt, Path path) {
    public SessionMetadata {
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (Objects.requireNonNull(cwd, "cwd").isBlank()) {
            throw new IllegalArgumentException("cwd must not be blank");
        }
        title = Objects.requireNonNull(title, "title");
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount must be non-negative");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        path = Objects.requireNonNull(path, "path");
    }
}
