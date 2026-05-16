package minicode.context.compact;

import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ManualCompactResult(CompactStatus status, List<ChatMessage> messages,
                                  Optional<CompressionBoundaryResult> boundary, Optional<String> reason) {
    public ManualCompactResult {
        status = Objects.requireNonNull(status, "status");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        boundary = Objects.requireNonNull(boundary, "boundary");
        reason = Objects.requireNonNull(reason, "reason");
        if (status == CompactStatus.COMPACTED && boundary.isEmpty()) {
            throw new IllegalArgumentException("COMPACTED result requires boundary");
        }
        if (status != CompactStatus.COMPACTED && boundary.isPresent()) {
            throw new IllegalArgumentException(status + " result must not carry boundary");
        }
    }

    public static ManualCompactResult compacted(List<ChatMessage> messages, CompressionBoundaryResult boundary) {
        return new ManualCompactResult(CompactStatus.COMPACTED, messages, Optional.of(boundary), Optional.empty());
    }

    public static ManualCompactResult skipped(List<ChatMessage> messages, String reason) {
        return new ManualCompactResult(CompactStatus.SKIPPED, messages, Optional.empty(), Optional.of(reason));
    }

    public static ManualCompactResult failed(List<ChatMessage> messages, String reason) {
        return new ManualCompactResult(CompactStatus.FAILED, messages, Optional.empty(), Optional.of(reason));
    }
}
