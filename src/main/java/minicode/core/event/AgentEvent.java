package minicode.core.event;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.context.compact.AutoCompactEventType;
import minicode.context.compact.CompressionResult;
import minicode.context.stats.ContextStats;
import minicode.core.turn.TurnCancellation;
import minicode.core.message.ChatMessage;
import minicode.tools.result.ToolResultReplacementRecord;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public sealed interface AgentEvent permits AgentEvent.AssistantMessageEvent, AgentEvent.ToolStartedEvent,
        AgentEvent.ToolFinishedEvent, AgentEvent.ContextStatsEvent, AgentEvent.AutoCompactEvent,
        AgentEvent.AwaitUserEvent, AgentEvent.TurnCancelledEvent, ToolResultsBudgetedEvent {
    String turnId();

    Instant timestamp();

    record AssistantMessageEvent(String turnId, Instant timestamp, ChatMessage message) implements AgentEvent {
        public AssistantMessageEvent {
            requireEvent(turnId, timestamp);
            message = Objects.requireNonNull(message, "message");
        }
    }

    record ToolStartedEvent(String turnId, Instant timestamp, String toolUseId, String toolName,
                            JsonNode input) implements AgentEvent {
        public ToolStartedEvent {
            requireEvent(turnId, timestamp);
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            input = Objects.requireNonNull(input, "input");
        }
    }

    record ToolFinishedEvent(String turnId, Instant timestamp, String toolUseId, String toolName,
                             boolean error, boolean awaitUser,
                             Optional<ToolResultReplacementRecord> replacement) implements AgentEvent {
        public ToolFinishedEvent {
            requireEvent(turnId, timestamp);
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            replacement = Objects.requireNonNull(replacement, "replacement");
        }
    }

    record ContextStatsEvent(String turnId, Instant timestamp, ContextStats stats) implements AgentEvent {
        public ContextStatsEvent {
            requireEvent(turnId, timestamp);
            stats = Objects.requireNonNull(stats, "stats");
        }
    }

    record AutoCompactEvent(String turnId, Instant timestamp, AutoCompactEventType type,
                            Optional<CompressionResult> result, Optional<String> reason) implements AgentEvent {
        public AutoCompactEvent {
            requireEvent(turnId, timestamp);
            type = Objects.requireNonNull(type, "type");
            result = Objects.requireNonNull(result, "result");
            reason = Objects.requireNonNull(reason, "reason");
            if (type == AutoCompactEventType.COMPLETED && result.isEmpty()) {
                throw new IllegalArgumentException("COMPLETED auto compact event requires result");
            }
            if (type != AutoCompactEventType.COMPLETED && result.isPresent()) {
                throw new IllegalArgumentException(type + " auto compact event must not carry result");
            }
        }
    }

    record AwaitUserEvent(String turnId, Instant timestamp, String toolUseId, String question) implements AgentEvent {
        public AwaitUserEvent {
            requireEvent(turnId, timestamp);
            requireText(toolUseId, "toolUseId");
            requireText(question, "question");
        }
    }

    record TurnCancelledEvent(String turnId, Instant timestamp, TurnCancellation cancellation) implements AgentEvent {
        public TurnCancelledEvent {
            requireEvent(turnId, timestamp);
            cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    private static void requireEvent(String turnId, Instant timestamp) {
        requireText(turnId, "turnId");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
