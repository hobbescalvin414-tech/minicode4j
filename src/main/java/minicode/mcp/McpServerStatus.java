package minicode.mcp;

public enum McpServerStatus {
    CONNECTING,
    CONNECTED,
    ERROR,
    DISABLED;

    public String displayName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
