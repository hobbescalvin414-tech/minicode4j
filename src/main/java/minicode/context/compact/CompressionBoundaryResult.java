package minicode.context.compact;

import minicode.core.message.ContextSummaryMessage;

import java.util.Objects;

public record CompressionBoundaryResult(ContextSummaryMessage summaryMessage, CompactMetadata metadata) {
    public CompressionBoundaryResult {
        summaryMessage = Objects.requireNonNull(summaryMessage, "summaryMessage");
        metadata = Objects.requireNonNull(metadata, "metadata");
    }
}
