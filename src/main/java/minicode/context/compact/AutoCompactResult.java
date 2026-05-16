package minicode.context.compact;

import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AutoCompactResult(CompactStatus status, List<ChatMessage> messages,
                                Optional<CompressionBoundaryResult> boundary,
                                Optional<String> reason) {
    public AutoCompactResult {
        status = Objects.requireNonNull(status, "status");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        boundary = Objects.requireNonNull(boundary, "boundary");
        reason = Objects.requireNonNull(reason, "reason");
        if (status == CompactStatus.COMPACTED && boundary.isEmpty()) {
            throw new IllegalArgumentException("COMPACTED auto compact result requires boundary");
        }
        if (status != CompactStatus.COMPACTED && boundary.isPresent()) {
            throw new IllegalArgumentException(status + " auto compact result must not carry boundary");
        }
    }

    public static AutoCompactResult compacted(List<ChatMessage> messages, CompressionBoundaryResult boundary) {
        return new AutoCompactResult(CompactStatus.COMPACTED, messages, Optional.of(boundary), Optional.empty());
    }

    public static AutoCompactResult skipped(List<ChatMessage> messages, String reason) {
        return new AutoCompactResult(CompactStatus.SKIPPED, messages, Optional.empty(), Optional.of(reason));
    }

    public static AutoCompactResult failed(List<ChatMessage> messages, String reason) {
        return new AutoCompactResult(CompactStatus.FAILED, messages, Optional.empty(), Optional.of(reason));
    }

    public CompressionResult compressionResult() {
        return new CompressionResult(messages, boundary);
    }
}
