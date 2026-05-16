package minicode.mcp;

import java.util.Objects;

public final class McpException extends RuntimeException {
    private final McpErrorKind kind;

    public McpException(McpErrorKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public McpException(McpErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public McpErrorKind kind() {
        return kind;
    }
}
