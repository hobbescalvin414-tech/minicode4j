package minicode.core.turn;

import java.util.Objects;

public final class CancellationRequestedException extends RuntimeException {
    private final TurnCancellation cancellation;

    public CancellationRequestedException(TurnCancellation cancellation) {
        super(Objects.requireNonNull(cancellation, "cancellation").reason());
        this.cancellation = cancellation;
    }

    public TurnCancellation cancellation() {
        return cancellation;
    }
}
