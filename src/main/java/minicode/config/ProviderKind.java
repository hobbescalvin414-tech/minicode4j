package minicode.config;

public enum ProviderKind {
    MOCK,
    ANTHROPIC;

    public static ProviderKind parse(String value) {
        if (value == null || value.isBlank()) {
            return ANTHROPIC;
        }
        return switch (value.trim().toLowerCase()) {
            case "mock" -> MOCK;
            case "anthropic", "anthropic-compatible" -> ANTHROPIC;
            default -> throw new RuntimeConfigException("Unsupported provider: " + value);
        };
    }
}
