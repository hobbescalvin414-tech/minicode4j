package minicode.app.ui;

import minicode.core.event.AgentEvent;
import minicode.core.event.ToolResultsBudgetedEvent;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ToolResultMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UiAgentEventProjector {
    private static final int PREVIEW_CHARS = 4_000;
    private final Map<String, ToolResultMessage> toolResultsById = new HashMap<>();
    private final Map<String, String> toolSummariesById = new HashMap<>();

    public List<UiEvent> project(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case AgentEvent.AssistantMessageEvent assistant -> projectAssistant(assistant);
            case AgentEvent.ToolStartedEvent started -> projectToolStarted(started);
            case AgentEvent.ToolFinishedEvent finished -> projectToolFinished(finished);
            case AgentEvent.ContextStatsEvent stats -> List.of(UiEvent.ContextStats.from(stats.stats()));
            case AgentEvent.AwaitUserEvent awaitUser -> List.of(new UiEvent.AwaitUser(
                    awaitUser.toolUseId(), stripAskUserPrefix(awaitUser.question())));
            case AgentEvent.TurnCancelledEvent ignored -> List.of();
            case AgentEvent.AutoCompactEvent ignored -> List.of();
            case ToolResultsBudgetedEvent ignored -> List.of();
        };
    }

    private List<UiEvent> projectAssistant(AgentEvent.AssistantMessageEvent event) {
        String idPrefix = event.turnId() + "-" + event.timestamp().toEpochMilli();
        return switch (event.message()) {
            case AssistantMessage assistant -> List.of(new UiEvent.AssistantMessage(
                    idPrefix + "-assistant", UiSafeText.redact(assistant.content())));
            case AssistantProgressMessage progress -> List.of(new UiEvent.AssistantProgress(
                    idPrefix + "-progress", UiSafeText.redact(progress.content())));
            case ToolResultMessage toolResult -> {
                toolResultsById.put(toolResult.toolUseId(), toolResult);
                yield List.of();
            }
            case AssistantToolCallMessage ignored -> List.of();
            case AssistantThinkingMessage ignored -> List.of();
            default -> List.of();
        };
    }

    private List<UiEvent> projectToolStarted(AgentEvent.ToolStartedEvent event) {
        if ("ask_user".equals(event.toolName())) {
            return List.of();
        }
        String summary = UiToolInputSummarizer.summarize(event.toolName(), event.input());
        toolSummariesById.put(event.toolUseId(), summary);
        return List.of(new UiEvent.ToolStarted(event.toolUseId(), event.toolName(), summary));
    }

    private List<UiEvent> projectToolFinished(AgentEvent.ToolFinishedEvent event) {
        if ("ask_user".equals(event.toolName())) {
            return List.of();
        }
        String content = event.replacement()
                .map(replacement -> replacement.preview().isBlank() ? "" : replacement.preview())
                .orElseGet(() -> {
                    ToolResultMessage result = toolResultsById.get(event.toolUseId());
                    return result == null ? "" : result.content();
                });
        UiSafeText.Preview preview = UiSafeText.preview(content, PREVIEW_CHARS);
        String storageRef = event.replacement()
                .map(replacement -> replacement.storageRef().id())
                .orElse(null);
        return List.of(new UiEvent.ToolFinished(
                event.toolUseId(),
                event.toolName(),
                event.error() ? "error" : "ok",
                toolSummariesById.getOrDefault(event.toolUseId(), ""),
                preview.text(),
                preview.truncated(),
                preview.hiddenLines(),
                storageRef,
                java.util.Optional.empty()
        ));
    }

    private static String stripAskUserPrefix(String question) {
        return question.startsWith("Question for user:")
                ? question.substring("Question for user:".length()).trim()
                : question;
    }
}
