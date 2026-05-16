package minicode.core.turn;

import java.util.Objects;
import java.util.Optional;

public record TurnError(String message, TurnErrorSource source, boolean retryable,
                        Optional<String> diagnostics, Optional<String> causeClass) {
    public TurnError {
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        source = Objects.requireNonNull(source, "source");
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        causeClass = Objects.requireNonNull(causeClass, "causeClass");
    }
}
