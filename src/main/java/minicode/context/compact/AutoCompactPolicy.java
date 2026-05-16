package minicode.context.compact;

public record AutoCompactPolicy(double utilizationThreshold, int maxFailures, long minEffectiveInput,
                                int failureCooldownPreflights) {
    public AutoCompactPolicy {
        if (utilizationThreshold <= 0.0d || utilizationThreshold > 1.0d) {
            throw new IllegalArgumentException("utilizationThreshold must be in (0, 1]");
        }
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
        if (minEffectiveInput < 0) {
            throw new IllegalArgumentException("minEffectiveInput must be non-negative");
        }
        if (failureCooldownPreflights < 0) {
            throw new IllegalArgumentException("failureCooldownPreflights must be non-negative");
        }
    }

    public static AutoCompactPolicy defaults() {
        return new AutoCompactPolicy(0.85d, 3, 20_000, 2);
    }
}
