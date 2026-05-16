package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

public record McpToolDescriptor(String name, String description, Optional<JsonNode> inputSchema) {
    public McpToolDescriptor {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
