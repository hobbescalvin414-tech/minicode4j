package minicode.mcp;

import java.util.Locale;

public final class McpToolName {
    private McpToolName() {
    }

    public static String wrappedName(String serverName, String toolName) {
        return "mcp__" + sanitize(serverName, "server") + "__" + sanitize(toolName, "tool");
    }

    static String sanitize(String value, String fallback) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? fallback : normalized;
    }
}
