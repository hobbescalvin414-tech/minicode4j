package minicode.core.message;

import java.util.Objects;

public record ToolResultMessage(String toolUseId, String toolName, String content, boolean error) implements ChatMessage {
    public ToolResultMessage {
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        content = Objects.requireNonNull(content, "content");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
