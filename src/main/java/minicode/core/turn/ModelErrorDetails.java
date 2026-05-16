package minicode.core.turn;

import java.util.Objects;

public record ModelErrorDetails(TurnError error) implements AgentTurnStopDetails {
    public ModelErrorDetails {
        error = Objects.requireNonNull(error, "error");
        if (error.source() != TurnErrorSource.MODEL) {
            throw new IllegalArgumentException("model error details require MODEL source");
        }
    }
}
