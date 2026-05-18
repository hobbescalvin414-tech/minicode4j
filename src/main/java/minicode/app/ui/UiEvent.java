package minicode.app.ui;

import minicode.context.accounting.TokenAccountingSource;
import minicode.context.stats.ContextStats;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface UiEvent permits UiEvent.Ready, UiEvent.HistoryItem, UiEvent.AssistantMessage,
        UiEvent.AssistantProgress, UiEvent.ToolStarted, UiEvent.ToolFinished, UiEvent.PermissionRequest,
        UiEvent.PermissionAudit, UiEvent.AwaitUser, UiEvent.ContextStats, UiEvent.Status, UiEvent.TurnStop,
        UiEvent.Error {
    record Ready(String sessionId, String cwd, String model) implements UiEvent {
        public Ready {
            requireText(sessionId, "sessionId");
            requireText(cwd, "cwd");
            requireText(model, "model");
        }
    }

    record HistoryItem(String id, String kind, String text) implements UiEvent {
        public HistoryItem {
            requireText(id, "id");
            if (!List.of("user", "assistant", "progress", "tool", "ask_user", "diagnostic", "compact", "meta")
                    .contains(Objects.requireNonNull(kind, "kind"))) {
                throw new IllegalArgumentException("unsupported history kind: " + kind);
            }
            text = Objects.requireNonNull(text, "text");
        }
    }

    record AssistantMessage(String id, String text) implements UiEvent {
        public AssistantMessage {
            requireText(id, "id");
            text = Objects.requireNonNull(text, "text");
        }
    }

    record AssistantProgress(String id, String text) implements UiEvent {
        public AssistantProgress {
            requireText(id, "id");
            text = Objects.requireNonNull(text, "text");
        }
    }

    record ToolStarted(String toolUseId, String toolName, String summary) implements UiEvent {
        public ToolStarted {
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    record ToolFinished(String toolUseId, String toolName, String status, String summary, String preview,
                        boolean truncated, int hiddenLines, String storageRef,
                        Optional<DiffPreview> diffPreview) implements UiEvent {
        public ToolFinished {
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            if (!List.of("ok", "error", "failed").contains(Objects.requireNonNull(status, "status"))) {
                throw new IllegalArgumentException("unsupported tool status: " + status);
            }
            summary = Objects.requireNonNull(summary, "summary");
            preview = Objects.requireNonNull(preview, "preview");
            if (hiddenLines < 0) {
                throw new IllegalArgumentException("hiddenLines must be non-negative");
            }
            diffPreview = Objects.requireNonNull(diffPreview, "diffPreview");
        }
    }

    record DiffPreview(String title, List<String> lines, boolean truncated, int hiddenLines) {
        public DiffPreview {
            title = Objects.requireNonNull(title, "title");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            if (hiddenLines < 0) {
                throw new IllegalArgumentException("hiddenLines must be non-negative");
            }
        }
    }

    record PermissionRequest(String requestId, String title, String body, List<String> facts,
                             List<PermissionChoice> choices) implements UiEvent {
        public PermissionRequest {
            requireText(requestId, "requestId");
            requireText(title, "title");
            requireText(body, "body");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        }
    }

    record PermissionChoice(String key, String label) {
        public PermissionChoice {
            requireText(key, "key");
            requireText(label, "label");
        }
    }

    record PermissionAudit(String requestId, String decision, String choiceKey, String summary) implements UiEvent {
        public PermissionAudit {
            requireText(requestId, "requestId");
            if (!List.of("allowed", "denied").contains(Objects.requireNonNull(decision, "decision"))) {
                throw new IllegalArgumentException("unsupported permission decision: " + decision);
            }
            requireText(choiceKey, "choiceKey");
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    record AwaitUser(String toolUseId, String question) implements UiEvent {
        public AwaitUser {
            requireText(toolUseId, "toolUseId");
            requireText(question, "question");
        }
    }

    record ContextStats(String badge, long totalTokens, long effectiveInput, String source,
                        String warning) implements UiEvent {
        public ContextStats {
            requireText(badge, "badge");
            if (totalTokens < 0 || effectiveInput <= 0) {
                throw new IllegalArgumentException("invalid context stats token counts");
            }
            if (!List.of("provider", "provider+estimate", "estimate")
                    .contains(Objects.requireNonNull(source, "source"))) {
                throw new IllegalArgumentException("unsupported context stats source: " + source);
            }
            if (!List.of("ok", "normal", "warning", "critical", "blocked")
                    .contains(Objects.requireNonNull(warning, "warning"))) {
                throw new IllegalArgumentException("unsupported context stats warning: " + warning);
            }
        }

        public static ContextStats from(minicode.context.stats.ContextStats stats) {
            Objects.requireNonNull(stats, "stats");
            long totalTokens = stats.accounting().totalTokens();
            String source = source(stats.accounting().source());
            String warning = stats.warningLevel().name().toLowerCase(java.util.Locale.ROOT);
            long percent = Math.round(stats.utilization() * 100.0d);
            return new ContextStats(
                    "context " + percent + "% " + warning + " " + totalTokens + "/" + stats.effectiveInput()
                            + " " + source,
                    totalTokens,
                    stats.effectiveInput(),
                    source,
                    warning
            );
        }

        private static String source(TokenAccountingSource source) {
            return switch (source) {
                case PROVIDER_USAGE -> "provider";
                case PROVIDER_USAGE_WITH_ESTIMATE -> "provider+estimate";
                case ESTIMATE_ONLY -> "estimate";
            };
        }
    }

    record Status(String text, boolean busy) implements UiEvent {
        public Status {
            text = Objects.requireNonNull(text, "text");
        }
    }

    record TurnStop(String reason, Optional<String> message) implements UiEvent {
        public TurnStop {
            if (!List.of("FINAL", "AWAIT_USER", "MAX_STEPS", "MODEL_ERROR", "CANCELLED", "EMPTY_RESPONSE_FALLBACK")
                    .contains(Objects.requireNonNull(reason, "reason"))) {
                throw new IllegalArgumentException("unsupported turn stop reason: " + reason);
            }
            message = Objects.requireNonNull(message, "message");
        }
    }

    record Error(String message, boolean recoverable) implements UiEvent {
        public Error {
            message = Objects.requireNonNull(message, "message");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
