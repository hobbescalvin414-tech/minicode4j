package minicode.session.transcript;

import java.util.Objects;
import java.util.Optional;

public record TranscriptEntry(Kind kind, String body, Optional<String> toolName, Optional<Boolean> error) {
    public TranscriptEntry {
        kind = Objects.requireNonNull(kind, "kind");
        body = Objects.requireNonNull(body, "body");
        toolName = Objects.requireNonNull(toolName, "toolName");
        error = Objects.requireNonNull(error, "error");
    }

    public enum Kind {
        USER,
        ASSISTANT,
        PROGRESS,
        TOOL,
        COMPACT
    }
}
