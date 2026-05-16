package minicode.permissions.model;

import java.util.Objects;
import java.util.Optional;

public class PermissionDeniedException extends RuntimeException {
    private final PermissionRequest request;
    private final Optional<String> choiceKey;
    private final Optional<String> feedback;

    public PermissionDeniedException(PermissionRequest request, Optional<String> feedback) {
        this(request, Optional.empty(), feedback);
    }

    public PermissionDeniedException(PermissionRequest request, Optional<String> choiceKey, Optional<String> feedback) {
        super(message(feedback));
        this.request = Objects.requireNonNull(request, "request");
        this.choiceKey = Objects.requireNonNull(choiceKey, "choiceKey");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    public PermissionRequest request() {
        return request;
    }

    public Optional<String> feedback() {
        return feedback;
    }

    public Optional<String> choiceKey() {
        return choiceKey;
    }

    private static String message(Optional<String> feedback) {
        Objects.requireNonNull(feedback, "feedback");
        return feedback.filter(value -> !value.isBlank())
                .map(value -> "Permission denied: " + value)
                .orElse("Permission denied");
    }
}
