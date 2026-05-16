package minicode.model.anthropic;

import minicode.model.ModelRequestException;

import java.util.Optional;

public final class ProviderRequestException extends ModelRequestException {
    public ProviderRequestException(String message) {
        this(message, Optional.empty(), false, null);
    }

    public ProviderRequestException(String message, Optional<Integer> statusCode, boolean retryable) {
        this(message, statusCode, retryable, null);
    }

    public ProviderRequestException(String message, Optional<Integer> statusCode, boolean retryable, Throwable cause) {
        super(message, statusCode, retryable, diagnostics(statusCode, retryable), cause);
    }

    private static Optional<String> diagnostics(Optional<Integer> statusCode, boolean retryable) {
        return statusCode.map(status -> "statusCode=" + status + "; retryable=" + retryable);
    }
}
