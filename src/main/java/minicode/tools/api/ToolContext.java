package minicode.tools.api;

import minicode.core.turn.CancellationToken;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ToolContext(Path cwd, String sessionId, Optional<String> turnId, Optional<String> toolUseId,
                          CancellationToken cancellationToken) {
    public ToolContext(Path cwd, String sessionId, Optional<String> turnId, Optional<String> toolUseId) {
        this(cwd, sessionId, turnId, toolUseId, CancellationToken.none());
    }

    public ToolContext {
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        turnId = Objects.requireNonNull(turnId, "turnId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
