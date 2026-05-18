package minicode.app.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public final class UiEventEncoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String encode(UiEvent event) {
        try {
            return MAPPER.writeValueAsString(toJson(event));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode UI event.", exception);
        }
    }

    ObjectNode toJson(UiEvent event) {
        return switch (event) {
            case UiEvent.Ready ready -> object("ready")
                    .put("sessionId", ready.sessionId())
                    .put("cwd", ready.cwd())
                    .put("model", ready.model());
            case UiEvent.HistoryItem item -> object("history_item")
                    .put("id", item.id())
                    .put("kind", item.kind())
                    .put("text", item.text());
            case UiEvent.AssistantMessage message -> object("assistant_message")
                    .put("id", message.id())
                    .put("text", message.text());
            case UiEvent.AssistantProgress progress -> object("assistant_progress")
                    .put("id", progress.id())
                    .put("text", progress.text());
            case UiEvent.ToolStarted started -> object("tool_started")
                    .put("toolUseId", started.toolUseId())
                    .put("toolName", started.toolName())
                    .put("summary", started.summary());
            case UiEvent.ToolFinished finished -> toolFinished(finished);
            case UiEvent.PermissionRequest request -> permissionRequest(request);
            case UiEvent.PermissionAudit audit -> object("permission_audit")
                    .put("requestId", audit.requestId())
                    .put("decision", audit.decision())
                    .put("choiceKey", audit.choiceKey())
                    .put("summary", audit.summary());
            case UiEvent.AwaitUser awaitUser -> object("await_user")
                    .put("toolUseId", awaitUser.toolUseId())
                    .put("question", awaitUser.question());
            case UiEvent.ContextStats stats -> object("context_stats")
                    .put("badge", stats.badge())
                    .put("totalTokens", stats.totalTokens())
                    .put("effectiveInput", stats.effectiveInput())
                    .put("source", stats.source())
                    .put("warning", stats.warning());
            case UiEvent.Status status -> object("status")
                    .put("text", status.text())
                    .put("busy", status.busy());
            case UiEvent.TurnStop stop -> {
                ObjectNode node = object("turn_stop").put("reason", stop.reason());
                stop.message().ifPresent(message -> node.put("message", message));
                yield node;
            }
            case UiEvent.Error error -> object("error")
                    .put("message", error.message())
                    .put("recoverable", error.recoverable());
        };
    }

    private ObjectNode toolFinished(UiEvent.ToolFinished finished) {
        ObjectNode node = object("tool_finished")
                .put("toolUseId", finished.toolUseId())
                .put("toolName", finished.toolName())
                .put("status", finished.status())
                .put("summary", finished.summary())
                .put("preview", finished.preview())
                .put("truncated", finished.truncated())
                .put("hiddenLines", finished.hiddenLines());
        if (finished.storageRef() == null) {
            node.putNull("storageRef");
        } else {
            node.put("storageRef", finished.storageRef());
        }
        finished.diffPreview().ifPresent(diff -> {
            ObjectNode diffNode = node.putObject("diffPreview")
                    .put("title", diff.title())
                    .put("truncated", diff.truncated())
                    .put("hiddenLines", diff.hiddenLines());
            var lines = diffNode.putArray("lines");
            diff.lines().forEach(lines::add);
        });
        return node;
    }

    private ObjectNode permissionRequest(UiEvent.PermissionRequest request) {
        ObjectNode node = object("permission_request")
                .put("requestId", request.requestId())
                .put("title", request.title())
                .put("body", request.body());
        var facts = node.putArray("facts");
        request.facts().forEach(facts::add);
        var choices = node.putArray("choices");
        request.choices().forEach(choice -> choices.addObject()
                .put("key", choice.key())
                .put("label", choice.label()));
        return node;
    }

    private static ObjectNode object(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }
}
