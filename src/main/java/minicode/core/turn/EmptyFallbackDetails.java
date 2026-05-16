package minicode.core.turn;

import java.util.Objects;
import java.util.Optional;

public record EmptyFallbackDetails(Optional<String> reason, Optional<String> diagnostics,
                                   boolean sawToolResultThisTurn, int toolErrorCount)
        implements AgentTurnStopDetails {
    public EmptyFallbackDetails {
        reason = Objects.requireNonNull(reason, "reason");
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (toolErrorCount < 0) {
            throw new IllegalArgumentException("toolErrorCount must be non-negative");
        }
    }

    public EmptyFallbackDetails(Optional<String> diagnostics) {
        this(diagnostics, diagnostics, false, 0);
    }
}
