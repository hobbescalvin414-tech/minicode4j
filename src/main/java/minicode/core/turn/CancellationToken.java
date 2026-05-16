package minicode.core.turn;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationToken {
    private static final CancellationToken NONE = new CancellationToken(false);

    private final boolean cancellable;
    private final AtomicReference<CancellationRequest> request = new AtomicReference<>();

    private CancellationToken(boolean cancellable) {
        this.cancellable = cancellable;
    }

    public static CancellationToken none() {
        return NONE;
    }

    public static CancellationToken create() {
        return new CancellationToken(true);
    }

    public static CancellationToken cancelled(CancellationSource source, String reason) {
        CancellationToken token = create();
        token.requestCancellation(source, reason);
        return token;
    }

    public boolean isCancellationRequested() {
        return request.get() != null;
    }

    public void requestCancellation(CancellationSource source, String reason) {
        if (!cancellable) {
            return;
        }
        CancellationRequest cancellationRequest = new CancellationRequest(source, reason);
        request.compareAndSet(null, cancellationRequest);
    }

    public Optional<TurnCancellation> cancellation(CancellationPhase phase) {
        CancellationRequest cancellationRequest = request.get();
        if (cancellationRequest == null) {
            return Optional.empty();
        }
        return Optional.of(cancellationRequest.toCancellation(phase));
    }

    public void throwIfCancellationRequested(CancellationPhase phase) {
        cancellation(phase).ifPresent(cancellation -> {
            throw new CancellationRequestedException(cancellation);
        });
    }

    private record CancellationRequest(CancellationSource source, String reason) {
        private CancellationRequest {
            source = Objects.requireNonNull(source, "source");
            if (Objects.requireNonNull(reason, "reason").isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }

        private TurnCancellation toCancellation(CancellationPhase phase) {
            return new TurnCancellation(source, phase, reason);
        }
    }
}
