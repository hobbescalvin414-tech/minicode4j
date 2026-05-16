package minicode.core.event;

import minicode.tools.result.ToolResultReplacementRecord;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ToolResultsBudgetedEvent(String turnId, Instant timestamp,
                                       List<ToolResultReplacementRecord> replacements) implements AgentEvent {
    public ToolResultsBudgetedEvent {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        replacements = List.copyOf(Objects.requireNonNull(replacements, "replacements"));
    }
}
