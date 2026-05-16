package minicode.core.turn;

import java.util.Objects;

public record CancellationDetails(TurnCancellation cancellation) implements AgentTurnStopDetails {
    public CancellationDetails {
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
}
