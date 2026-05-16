package minicode.tools.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ValidationResult(boolean valid, Optional<JsonNode> normalizedInput, List<String> errors) {
    public ValidationResult {
        normalizedInput = Objects.requireNonNull(normalizedInput, "normalizedInput");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("valid validation result cannot carry errors");
        }
        if (!valid && errors.isEmpty()) {
            throw new IllegalArgumentException("invalid validation result requires errors");
        }
    }

    public static ValidationResult valid(JsonNode normalizedInput) {
        return new ValidationResult(true, Optional.of(Objects.requireNonNull(normalizedInput, "normalizedInput")), List.of());
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, Optional.empty(), errors);
    }
}
