package minicode.core.message;

import java.util.Objects;

public record SystemMessage(String content) implements ChatMessage {
    public SystemMessage {
        content = Objects.requireNonNull(content, "content");
    }
}
