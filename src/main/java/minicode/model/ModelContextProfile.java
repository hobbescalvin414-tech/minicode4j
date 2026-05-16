package minicode.model;

import java.util.Objects;
import java.util.Optional;

public record ModelContextProfile(long contextWindow, long outputReserve, int resolvedMaxOutputTokens, Source source,
                                  Optional<Integer> providerMaxOutputTokens) {
    public ModelContextProfile {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
        if (resolvedMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("resolvedMaxOutputTokens must be positive");
        }
        source = Objects.requireNonNull(source, "source");
        providerMaxOutputTokens = Objects.requireNonNull(providerMaxOutputTokens, "providerMaxOutputTokens");
    }

    public long effectiveInput() {
        return contextWindow - outputReserve;
    }

    public enum Source {
        PROVIDER_METADATA,
        RUNTIME_CONFIG,
        LOCAL_MODEL_LIMITS,
        UNKNOWN_FALLBACK
    }
}
