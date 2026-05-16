package minicode.tools.result;

import minicode.core.message.ToolResultMessage;

import java.util.Objects;
import java.util.Optional;

public record ToolResultReplacementResult(ToolResultMessage message,
                                          Optional<ToolResultReplacementRecord> replacement) {
    public ToolResultReplacementResult {
        message = Objects.requireNonNull(message, "message");
        replacement = Objects.requireNonNull(replacement, "replacement");
        if (replacement.isPresent()) {
            ToolResultReplacementRecord record = replacement.get();
            if (!message.content().equals(record.replacementContent())) {
                throw new IllegalArgumentException("replacement content must match tool result message content");
            }
        }
    }
}
