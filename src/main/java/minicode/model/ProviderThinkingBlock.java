package minicode.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ProviderThinkingBlock(String type, JsonNode raw) {
    public ProviderThinkingBlock {
        if (Objects.requireNonNull(type, "type").isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        raw = Objects.requireNonNull(raw, "raw");
    }
}
