package minicode.mcp;

import java.util.Objects;
import java.util.Optional;

public record McpServerSummary(String name, String command, McpServerStatus status, int toolCount,
                               Optional<String> error, Optional<McpErrorKind> errorKind) {
    public McpServerSummary(String name, String command, McpServerStatus status, int toolCount,
                            Optional<String> error) {
        this(name, command, status, toolCount, error, Optional.empty());
    }

    public McpServerSummary {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        command = Objects.requireNonNull(command, "command");
        status = Objects.requireNonNull(status, "status");
        if (toolCount < 0) {
            throw new IllegalArgumentException("toolCount must be non-negative");
        }
        error = Objects.requireNonNull(error, "error");
        errorKind = Objects.requireNonNull(errorKind, "errorKind");
    }
}
