package minicode.model;

import java.util.Objects;
import java.util.Optional;

public record ModelMetadata(String id, Optional<Long> maxInputTokens, Optional<Integer> maxOutputTokens) {
    public ModelMetadata {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        maxInputTokens = Objects.requireNonNull(maxInputTokens, "maxInputTokens");
        maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
    }
}
