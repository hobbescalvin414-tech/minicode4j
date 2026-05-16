package minicode.core.turn;

import java.util.Objects;

public record TurnCancellation(CancellationSource source, CancellationPhase phase, String reason) {
    public TurnCancellation {
        source = Objects.requireNonNull(source, "source");
        phase = Objects.requireNonNull(phase, "phase");
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
