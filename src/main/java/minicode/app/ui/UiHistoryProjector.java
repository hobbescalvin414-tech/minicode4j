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
    public List<UiEvent> project(List<SessionEvent> events) {
        List<UiEvent> result = new ArrayList<>();
        for (SessionEvent event : List.copyOf(Objects.requireNonNull(events, "events"))) {
            if (event.type() == SessionEventType.COMPACT_BOUNDARY) {
                result.add(new UiEvent.HistoryItem(event.uuid(), "compact", "Context compacted"));
                continue;
            }
            event.message().flatMap(message -> switch (message) {
                case UserMessage user -> java.util.Optional.of(new UiEvent.HistoryItem(
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
}
