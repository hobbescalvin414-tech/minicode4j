package minicode.session.transcript;

import minicode.core.message.*;
import minicode.session.model.SessionEvent;
import minicode.session.model.SessionEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SessionTranscriptProjector {
    public List<TranscriptEntry> project(List<SessionEvent> events) {
        List<TranscriptEntry> entries = new ArrayList<>();
        for (SessionEvent event : events) {
            if (event.type() == SessionEventType.COMPACT_BOUNDARY) {
                entries.add(new TranscriptEntry(
                        TranscriptEntry.Kind.COMPACT,
                        event.compactMetadata()
                                .map(metadata -> "Context compacted: " + metadata.tokensBefore()
                                        + " -> " + metadata.tokensAfter() + " tokens")
                                .orElse("Context compacted"),
                        Optional.empty(),
                        Optional.empty()
                ));
                continue;
            }
            event.message().ifPresent(message -> entries.add(projectMessage(message)));
        }
        return List.copyOf(entries);
    }

    private TranscriptEntry projectMessage(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> new TranscriptEntry(
                    TranscriptEntry.Kind.USER, user.content(), Optional.empty(), Optional.empty());
            case AssistantMessage assistant -> new TranscriptEntry(
                    TranscriptEntry.Kind.ASSISTANT, assistant.content(), Optional.empty(), Optional.empty());
            case AssistantProgressMessage progress -> new TranscriptEntry(
                    TranscriptEntry.Kind.PROGRESS, progress.content(), Optional.empty(), Optional.empty());
            case AssistantToolCallMessage toolCall -> new TranscriptEntry(
                    TranscriptEntry.Kind.TOOL, "tool_call " + toolCall.toolUseId(),
                    Optional.of(toolCall.toolName()), Optional.empty());
            case ToolResultMessage toolResult -> new TranscriptEntry(
                    TranscriptEntry.Kind.TOOL, toolResult.content(),
                    Optional.of(toolResult.toolName()), Optional.of(toolResult.error()));
            case ContextSummaryMessage summary -> new TranscriptEntry(
                    TranscriptEntry.Kind.COMPACT, summary.content(), Optional.empty(), Optional.empty());
            case AssistantThinkingMessage thinking -> new TranscriptEntry(
                    TranscriptEntry.Kind.PROGRESS, "thinking blocks: " + thinking.blocks().size(),
                    Optional.empty(), Optional.empty());
            case SystemMessage system -> new TranscriptEntry(
                    TranscriptEntry.Kind.ASSISTANT, system.content(), Optional.empty(), Optional.empty());
            default -> throw new IllegalArgumentException("Unsupported message type: " + message.getClass().getName());
        };
    }
}
