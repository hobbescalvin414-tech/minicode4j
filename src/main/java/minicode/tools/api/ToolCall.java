package minicode.tools.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolCall(String id, String toolName, JsonNode input) {
    public ToolCall {
        requireText(id, "id");
        requireText(toolName, "toolName");
        input = Objects.requireNonNull(input, "input");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
