package minicode.app.ui;

import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.UserMessage;
import minicode.session.model.SessionEvent;
import minicode.session.model.SessionEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UiHistoryProjector {
    private static final java.util.Set<String> INTERNAL_USER_PROMPTS = java.util.Set.of(
            "Continue immediately from your <progress> update with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.",
            "Your last response was empty. Continue immediately with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.",
            "Your last response was empty after recent tool results. Continue immediately by trying the next concrete step, adapting to any tool errors, or giving an explicit <final> answer only if the task is complete.",
            "Your last response was empty after recent tool results that included errors. Adapt to the tool error, try the next concrete step, or give an explicit <final> answer only if the task is complete.",
            "Your previous response hit max_tokens during thinking before producing the next actionable step. Resume immediately and continue with the next concrete tool call, code change, or an explicit <final> answer only if the task is complete. Do not repeat the earlier plan.",
            "Resume from the previous pause_turn and continue the task immediately. Produce the next concrete tool call, code change, or an explicit <final> answer only if the task is complete."
    );

    public List<UiEvent> project(List<SessionEvent> events) {
        List<UiEvent> result = new ArrayList<>();
        for (SessionEvent event : List.copyOf(Objects.requireNonNull(events, "events"))) {
            if (event.type() == SessionEventType.COMPACT_BOUNDARY) {
                result.add(new UiEvent.HistoryItem(event.uuid(), "compact", "Context compacted"));
                continue;
            }
            event.message().flatMap(message -> switch (message) {
                case UserMessage user -> isInternalUserPrompt(user.content())
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(new UiEvent.HistoryItem(
                                event.uuid(), "user", UiSafeText.redact(user.content())));
                case AssistantMessage assistant -> java.util.Optional.of(new UiEvent.HistoryItem(
                        event.uuid(), "assistant", UiSafeText.redact(assistant.content())));
                case AssistantProgressMessage progress -> java.util.Optional.of(new UiEvent.HistoryItem(
                        event.uuid(), "progress", UiSafeText.redact(progress.content())));
                case ContextSummaryMessage summary -> java.util.Optional.of(new UiEvent.HistoryItem(
                        event.uuid(), "compact", UiSafeText.redact(summary.content())));
                case SystemMessage ignored -> java.util.Optional.empty();
                default -> java.util.Optional.empty();
            }).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private static boolean isInternalUserPrompt(String content) {
        return INTERNAL_USER_PROMPTS.contains(content);
    }
}
