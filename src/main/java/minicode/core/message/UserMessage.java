package minicode.core.message;

import java.util.Objects;

public record UserMessage(String content) implements ChatMessage {
    public UserMessage {
        content = Objects.requireNonNull(content, "content");
    }
}
