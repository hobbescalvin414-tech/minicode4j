package minicode.core.message;

import minicode.model.ProviderThinkingBlock;

import java.util.List;
import java.util.Objects;

public record AssistantThinkingMessage(List<ProviderThinkingBlock> blocks) implements ChatMessage {
    public AssistantThinkingMessage {
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    }
}
