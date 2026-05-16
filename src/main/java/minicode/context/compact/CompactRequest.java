package minicode.context.compact;

import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

public record CompactRequest(List<ChatMessage> messages, ModelAdapter modelAdapter, CompactTrigger trigger) {
    public CompactRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        trigger = Objects.requireNonNull(trigger, "trigger");
    }
}
