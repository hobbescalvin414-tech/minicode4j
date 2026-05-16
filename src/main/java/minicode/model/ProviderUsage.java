package minicode.model;

public record ProviderUsage(int inputTokens, int outputTokens, int totalTokens) {
    public ProviderUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("usage token counts must be non-negative");
        }
    }
}
