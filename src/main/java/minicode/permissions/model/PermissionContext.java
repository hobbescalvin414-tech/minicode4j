package minicode.permissions.model;

import java.util.Objects;
import java.util.Optional;

public record PermissionContext(String sessionId, Optional<String> turnId, Optional<String> toolUseId) {
    public PermissionContext {
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        turnId = Objects.requireNonNull(turnId, "turnId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
    }
}
