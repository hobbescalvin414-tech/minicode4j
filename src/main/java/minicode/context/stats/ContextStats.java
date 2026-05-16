package minicode.context.stats;

import minicode.context.accounting.TokenAccountingResult;

import java.util.Objects;

public record ContextStats(TokenAccountingResult accounting, long contextWindow, long outputReserve,
                           long effectiveInput, double utilization,
                           ContextWarningLevel warningLevel) {
    public ContextStats {
        accounting = Objects.requireNonNull(accounting, "accounting");
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
        if (effectiveInput <= 0 || effectiveInput != contextWindow - outputReserve) {
            throw new IllegalArgumentException("effectiveInput must equal contextWindow - outputReserve");
        }
        if (utilization < 0.0d) {
            throw new IllegalArgumentException("utilization must be non-negative");
        }
        warningLevel = Objects.requireNonNull(warningLevel, "warningLevel");
    }

    public long maxTokens() {
        return effectiveInput;
    }
}
