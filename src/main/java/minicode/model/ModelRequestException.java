package minicode.model;

import java.util.Objects;
import java.util.Optional;

public class ModelRequestException extends RuntimeException {
    private final Optional<Integer> statusCode;
    private final boolean retryable;
    private final Optional<String> diagnostics;

    public ModelRequestException(String message, Optional<Integer> statusCode, boolean retryable,
                                 Optional<String> diagnostics) {
        this(message, statusCode, retryable, diagnostics, null);
    }

    public ModelRequestException(String message, Optional<Integer> statusCode, boolean retryable,
                                 Optional<String> diagnostics, Throwable cause) {
        super(message, cause);
        this.statusCode = Objects.requireNonNull(statusCode, "statusCode");
        this.retryable = retryable;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public Optional<Integer> statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Optional<String> diagnostics() {
        return diagnostics;
    }
}
