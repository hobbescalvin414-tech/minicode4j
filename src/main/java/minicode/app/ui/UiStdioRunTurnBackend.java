package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.app.ApplicationServices;
import minicode.core.event.AgentEvent;
import minicode.core.event.ToolResultsBudgetedEvent;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;
import minicode.model.MockModelAdapter;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.tools.api.ToolCall;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Mock provider integration backend for TS UI protocol exercises.
 *
 * <p>TS-UI real adapter work must use the strict {@link UiEvent} DTO and
 * {@link UiEventEncoder} path instead of copying this mock-run ObjectNode writer.</p>
 */
public final class UiStdioRunTurnBackend {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PREVIEW_CHARS = 4_000;
    private static final int DEFAULT_MAX_STEPS = 4;

    private final ModelAdapter modelAdapter;

    public UiStdioRunTurnBackend() {
        this(MockModelAdapter.toolThenFinal(
                new ToolCall(
                        "mock-tool-1",
                        "list_files",
                        MAPPER.createObjectNode()
                                .put("path", ".")
                                .put("maxDepth", 1)
                                .put("limit", 40)
                ),
                "Mock runTurn completed."
        ));
    }

    public UiStdioRunTurnBackend(ModelAdapter modelAdapter) {
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
    }

    public void run(Path defaultHome, Path defaultCwd, InputStream input, OutputStream output, int defaultMaxSteps) {
        Session session = new Session(
                Objects.requireNonNull(defaultHome, "defaultHome").toAbsolutePath().normalize(),
                Objects.requireNonNull(defaultCwd, "defaultCwd").toAbsolutePath().normalize(),
                UUID.randomUUID().toString(),
                defaultMaxSteps > 0 ? defaultMaxSteps : DEFAULT_MAX_STEPS
        );
        PrintWriter writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
        UiEventProjector projector = new UiEventProjector(writer);
        ApplicationServices services = null;
        PendingPermission pendingPermission = null;
        PendingAskUser pendingAskUser = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode command = MAPPER.readTree(line);
                String type = command.path("type").asText("");
                switch (type) {
                    case "init" -> {
                        if (services != null) {
                            services.close();
                        }
                        session = session.fromInit(command);
                        services = createServices(session, projector);
                        projector.emitReady(session, "mock-ui-model");
                    }
                    case "user_submit" -> {
                        String text = command.path("text").asText("");
                        if (text.isBlank()) {
                            projector.emitError("user_submit text must not be blank", true);
                            continue;
                        }
                        if (controlledToolDisplayFlow(text, projector)) {
                            continue;
                        }
                        Optional<PendingPermission> permissionFlow = controlledPermissionFlow(text, projector);
                        if (permissionFlow.isPresent()) {
                            pendingPermission = permissionFlow.orElseThrow();
                            continue;
                        }
                        Optional<PendingAskUser> askUserFlow = controlledAskUserFlow(text, projector);
                        if (askUserFlow.isPresent()) {
                            pendingAskUser = askUserFlow.orElseThrow();
                            continue;
                        }
                        if (services == null) {
                            services = createServices(session, projector);
                            projector.emitReady(session, "mock-ui-model");
                        }
                        runUserTurn(text, services, session, projector);
                    }
                    case "permission_response" -> {
                        pendingPermission = handlePermissionResponse(command, pendingPermission, projector);
                    }
                    case "ask_user_answer" -> {
                        pendingAskUser = handleAskUserAnswer(command, pendingAskUser, projector);
                    }
                    case "shutdown" -> {
                        return;
                    }
                    default -> projector.emitError("Unsupported UI command: " + type, true);
                }
            }
        } catch (IOException exception) {
            projector.emitError("UI stdio failure: " + safeMessage(exception), false);
        } catch (RuntimeException exception) {
            projector.emitError("UI backend failure: " + safeMessage(exception), false);
        } finally {
            if (services != null) {
                services.close();
            }
            writer.flush();
        }
    }

    private boolean controlledToolDisplayFlow(String text, UiEventProjector projector) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT).trim();
        return switch (normalized) {
            case "/mock tool" -> {
                emitMockToolSuccessFlow(projector);
                yield true;
            }
            case "/mock diff" -> {
                emitMockDiffFlow(projector);
                yield true;
            }
            case "/mock tool-fail" -> {
                emitMockToolFailureFlow(projector);
                yield true;
            }
            default -> false;
        };
    }

    private void emitMockToolSuccessFlow(UiEventProjector projector) {
        projector.emitStatus("Running mock tool display flow...", true);
        projector.emit(object("tool_started")
                .put("toolUseId", "mock-tool-success")
                .put("toolName", "read_file")
                .put("summary", "path=README.md"));
        projector.emit(object("tool_finished")
                .put("toolUseId", "mock-tool-success")
                .put("toolName", "read_file")
                .put("status", "ok")
                .put("summary", "path=README.md")
                .put("preview", "FILE: README.md\nMiniCode4j mock preview")
                .put("truncated", false)
                .put("hiddenLines", 0)
                .putNull("storageRef"));

        projector.emit(object("tool_started")
                .put("toolUseId", "mock-tool-large")
                .put("toolName", "run_command")
                .put("summary", "cmd=\"mvn test\""));
        projector.emit(object("tool_finished")
                .put("toolUseId", "mock-tool-large")
                .put("toolName", "run_command")
                .put("status", "ok")
                .put("summary", "cmd=\"mvn test\"")
                .put("preview", "line 1\nline 2\nline 3")
                .put("truncated", true)
                .put("hiddenLines", 42)
                .put("storageRef", "mock-storage-run-command-1"));
        projector.emitStatus("Ready", false);
        projector.emitTurnStop("FINAL");
    }

    private void emitMockDiffFlow(UiEventProjector projector) {
        projector.emitStatus("Rendering mock diff preview...", true);
        projector.emit(object("tool_started")
                .put("toolUseId", "mock-tool-diff")
                .put("toolName", "edit_file")
                .put("summary", "path=README.md"));
        ObjectNode event = object("tool_finished")
                .put("toolUseId", "mock-tool-diff")
                .put("toolName", "edit_file")
                .put("status", "ok")
                .put("summary", "path=README.md")
                .put("preview", "Updated README.md")
                .put("truncated", false)
                .put("hiddenLines", 0)
                .putNull("storageRef");
        ObjectNode diff = event.putObject("diffPreview")
                .put("title", "README.md")
                .put("truncated", true)
                .put("hiddenLines", 5);
        diff.putArray("lines")
                .add("--- README.md")
                .add("+++ README.md")
                .add("-Old line")
                .add("+New line");
        projector.emit(event);
        projector.emitStatus("Ready", false);
        projector.emitTurnStop("FINAL");
    }

    private void emitMockToolFailureFlow(UiEventProjector projector) {
        projector.emitStatus("Running mock failed tool flow...", true);
        projector.emit(object("tool_started")
                .put("toolUseId", "mock-tool-fail")
                .put("toolName", "run_command")
                .put("summary", "cmd=\"mvn test\""));
        projector.emit(object("tool_finished")
                .put("toolUseId", "mock-tool-fail")
                .put("toolName", "run_command")
                .put("status", "error")
                .put("summary", "cmd=\"mvn test\"")
                .put("preview", "BUILD FAILURE\nMock command failed before execution in TS-UI-3 flow.")
                .put("truncated", false)
                .put("hiddenLines", 0)
                .putNull("storageRef"));
        projector.emitStatus("Ready", false);
        projector.emitTurnStop("FINAL");
    }

    private Optional<PendingPermission> controlledPermissionFlow(String text, UiEventProjector projector) {
        if (!text.toLowerCase(java.util.Locale.ROOT).contains("permission")) {
            return Optional.empty();
        }
        PendingPermission pending = PendingPermission.mock();
        projector.emitStatus("Waiting for approval...", true);
        projector.emitPermissionRequest(pending);
        return Optional.of(pending);
    }

    private Optional<PendingAskUser> controlledAskUserFlow(String text, UiEventProjector projector) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.contains("ask_user") && !normalized.contains("ask user")) {
            return Optional.empty();
        }
        PendingAskUser pending = new PendingAskUser(
                "mock-ask-user-1",
                "Which file should the mock flow use?"
        );
        projector.emitStatus("Waiting for user answer...", true);
        projector.emit(object("await_user")
                .put("toolUseId", pending.toolUseId())
                .put("question", pending.question()));
        projector.emitTurnStop("AWAIT_USER");
        return Optional.of(pending);
    }

    private PendingPermission handlePermissionResponse(JsonNode command, PendingPermission pending,
                                                       UiEventProjector projector) {
        if (pending == null) {
            projector.emitError("No pending permission request.", true);
            return null;
        }
        String requestId = command.path("requestId").asText("");
        if (!pending.requestId().equals(requestId)) {
            projector.emitError("Permission response requestId does not match pending request.", true);
            return pending;
        }
        String choiceKey = command.path("choiceKey").asText("");
        Optional<PermissionUiChoice> choice = pending.choice(choiceKey);
        if (choice.isEmpty()) {
            projector.emitError("Unknown permission choice: " + choiceKey, true);
            return pending;
        }
        String feedback = command.path("feedback").isMissingNode() || command.path("feedback").isNull()
                ? ""
                : command.path("feedback").asText("");
        PermissionUiChoice actualChoice = choice.orElseThrow();
        if (actualChoice.requiresFeedback() && feedback.isBlank()) {
            projector.emitError("Permission choice requires feedback: " + choiceKey, true);
            return pending;
        }
        String decision = actualChoice.allowed() ? "allowed" : "denied";
        projector.emitPermissionAudit(pending.requestId(), decision, choiceKey,
                decision + " " + choiceKey);
        String message = actualChoice.allowed()
                ? "Mock permission flow continued after approval."
                : feedback.isBlank()
                ? "Mock permission flow continued after denial."
                : "Mock permission flow continued after denial feedback: " + feedback;
        projector.emitAssistantMessage(message);
        projector.emitTurnStop("FINAL");
        return null;
    }

    private PendingAskUser handleAskUserAnswer(JsonNode command, PendingAskUser pending,
                                               UiEventProjector projector) {
        if (pending == null) {
            projector.emitError("No pending ask_user request.", true);
            return null;
        }
        String toolUseId = command.path("toolUseId").asText("");
        if (!pending.toolUseId().equals(toolUseId)) {
            projector.emitError("ask_user_answer toolUseId does not match pending request.", true);
            return pending;
        }
        String text = command.path("text").asText("");
        if (text.isBlank()) {
            projector.emitError("ask_user_answer text must not be blank", true);
            return pending;
        }
        projector.emitStatus("Thinking...", true);
        projector.emitAssistantMessage("Mock ask_user flow continued with answer: " + text);
        projector.emitTurnStop("FINAL");
        return null;
    }

    private ApplicationServices createServices(Session session, UiEventProjector projector) {
        return ApplicationServices.create(
                session.home(),
                session.cwd(),
                session.sessionId(),
                modelAdapter,
                projector::onEvent,
                PermissionPromptHandler.unavailable()
        );
    }

    private void runUserTurn(String text, ApplicationServices services, Session session, UiEventProjector projector) {
        if (text.isBlank()) {
            projector.emitError("user_submit text must not be blank", true);
            return;
        }
        projector.emitStatus("Thinking...", true);
        AgentTurnResult result = services.runTurn(services.turnRequest(
                appendUserMessage(services.sessionMessages(), text),
                session.maxSteps()
        ));
        services.sessionPersistenceRunner().apply(result.persistencePlan());
        projector.emitTurnStop(result);
    }

    private static List<minicode.core.message.ChatMessage> appendUserMessage(
            List<minicode.core.message.ChatMessage> existingMessages,
            String text
    ) {
        java.util.ArrayList<minicode.core.message.ChatMessage> messages = new java.util.ArrayList<>(existingMessages);
        messages.add(new UserMessage(text));
        return List.copyOf(messages);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private record Session(Path home, Path cwd, String sessionId, int maxSteps) {
        private Session fromInit(JsonNode command) {
            Path actualHome = command.hasNonNull("home")
                    ? Path.of(command.get("home").asText()).toAbsolutePath().normalize()
                    : home;
            Path actualCwd = command.hasNonNull("cwd")
                    ? Path.of(command.get("cwd").asText()).toAbsolutePath().normalize()
                    : cwd;
            String actualSessionId = command.hasNonNull("sessionId")
                    ? command.get("sessionId").asText()
                    : sessionId;
            int actualMaxSteps = command.hasNonNull("maxSteps") ? command.get("maxSteps").asInt() : maxSteps;
            if (actualSessionId.isBlank()) {
                actualSessionId = UUID.randomUUID().toString();
            }
            if (actualMaxSteps <= 0) {
                actualMaxSteps = DEFAULT_MAX_STEPS;
            }
            return new Session(actualHome, actualCwd, actualSessionId, actualMaxSteps);
        }
    }

    private record PendingAskUser(String toolUseId, String question) {
    }

    private record PermissionUiChoice(String key, String label, boolean allowed, boolean requiresFeedback) {
    }

    private record PendingPermission(String requestId, String title, String body, List<String> facts,
                                     List<PermissionUiChoice> choices) {
        private static PendingPermission mock() {
            return new PendingPermission(
                    "mock-permission-1",
                    "Command execution",
                    "The mock flow requested approval for a sensitive action. No command will be executed.",
                    List.of("Command: mock sensitive command", "Execution: skipped in TS-UI-2 mock flow"),
                    List.of(
                            new PermissionUiChoice("allow_once", "Allow once", true, false),
                            new PermissionUiChoice("deny_once", "Deny once", false, false),
                            new PermissionUiChoice("deny_feedback", "Deny with feedback", false, true)
                    )
            );
        }

        private Optional<PermissionUiChoice> choice(String key) {
            return choices.stream()
                    .filter(choice -> choice.key().equals(key))
                    .findFirst();
        }
    }

    private static final class UiEventProjector {
        private final PrintWriter writer;
        private final Map<String, ToolResultMessage> toolResultsById = new HashMap<>();
        private final Map<String, String> toolSummariesById = new HashMap<>();

        private UiEventProjector(PrintWriter writer) {
            this.writer = Objects.requireNonNull(writer, "writer");
        }

        private void onEvent(AgentEvent event) {
            switch (event) {
                case AgentEvent.AssistantMessageEvent assistantEvent -> emitAssistantMessageEvent(assistantEvent);
                case AgentEvent.ToolStartedEvent toolStarted -> emitToolStarted(toolStarted);
                case AgentEvent.ToolFinishedEvent toolFinished -> emitToolFinished(toolFinished);
                case AgentEvent.ContextStatsEvent stats -> emitContextStats(stats);
                case AgentEvent.AwaitUserEvent awaitUser -> emit(object("await_user")
                        .put("toolUseId", awaitUser.toolUseId())
                        .put("question", awaitUser.question()));
                case AgentEvent.TurnCancelledEvent ignored -> {
                }
                case AgentEvent.AutoCompactEvent ignored -> {
                }
                case ToolResultsBudgetedEvent ignored -> {
                }
            }
        }

        private void emitReady(Session session, String model) {
            emit(object("ready")
                    .put("sessionId", session.sessionId())
                    .put("cwd", session.cwd().toString())
                    .put("model", model));
        }

        private void emitStatus(String text, boolean busy) {
            emit(object("status").put("text", text).put("busy", busy));
        }

        private void emitError(String message, boolean recoverable) {
            emit(object("error").put("message", message).put("recoverable", recoverable));
        }

        private void emitTurnStop(AgentTurnResult result) {
            ObjectNode event = object("turn_stop").put("reason", result.stopReason().name());
            stopMessage(result).ifPresent(message -> event.put("message", message));
            emit(event);
        }

        private void emitTurnStop(String reason) {
            emit(object("turn_stop").put("reason", reason));
        }

        private void emitAssistantMessage(String text) {
            emit(object("assistant_message")
                    .put("id", "mock-" + UUID.randomUUID())
                    .put("text", text));
        }

        private void emitPermissionRequest(PendingPermission pending) {
            ObjectNode event = object("permission_request")
                    .put("requestId", pending.requestId())
                    .put("title", pending.title())
                    .put("body", pending.body());
            var facts = event.putArray("facts");
            pending.facts().forEach(facts::add);
            var choices = event.putArray("choices");
            for (PermissionUiChoice choice : pending.choices()) {
                choices.addObject()
                        .put("key", choice.key())
                        .put("label", choice.label());
            }
            emit(event);
        }

        private void emitPermissionAudit(String requestId, String decision, String choiceKey, String summary) {
            emit(object("permission_audit")
                    .put("requestId", requestId)
                    .put("decision", decision)
                    .put("choiceKey", choiceKey)
                    .put("summary", summary));
        }

        private Optional<String> stopMessage(AgentTurnResult result) {
            return switch (result.stopReason()) {
                case FINAL, AWAIT_USER -> Optional.empty();
                case MAX_STEPS -> Optional.of("Type continue to keep going from the current context.");
                case MODEL_ERROR -> result.stopDetails()
                        .filter(ModelErrorDetails.class::isInstance)
                        .map(ModelErrorDetails.class::cast)
                        .map(details -> details.error().message());
                case CANCELLED -> result.stopDetails()
                        .filter(CancellationDetails.class::isInstance)
                        .map(CancellationDetails.class::cast)
                        .map(details -> details.cancellation().reason());
                case EMPTY_RESPONSE_FALLBACK -> result.stopDetails()
                        .filter(EmptyFallbackDetails.class::isInstance)
                        .map(EmptyFallbackDetails.class::cast)
                        .flatMap(EmptyFallbackDetails::reason);
            };
        }

        private void emitAssistantMessageEvent(AgentEvent.AssistantMessageEvent event) {
            switch (event.message()) {
                case AssistantMessage assistant -> emit(object("assistant_message")
                        .put("id", event.turnId() + "-assistant-" + event.timestamp().toEpochMilli())
                        .put("text", assistant.content()));
                case AssistantProgressMessage progress -> emit(object("assistant_progress")
                        .put("id", event.turnId() + "-progress-" + event.timestamp().toEpochMilli())
                        .put("text", progress.content()));
                case ToolResultMessage toolResult -> toolResultsById.put(toolResult.toolUseId(), toolResult);
                case AssistantToolCallMessage ignored -> {
                }
                case AssistantThinkingMessage ignored -> {
                }
                default -> {
                }
            }
        }

        private void emitToolStarted(AgentEvent.ToolStartedEvent event) {
            String summary = summarizeToolInput(event.toolName(), event.input());
            toolSummariesById.put(event.toolUseId(), summary);
            emit(object("tool_started")
                    .put("toolUseId", event.toolUseId())
                    .put("toolName", event.toolName())
                    .put("summary", summary));
        }

        private void emitToolFinished(AgentEvent.ToolFinishedEvent event) {
            ToolResultMessage result = toolResultsById.get(event.toolUseId());
            String content = event.replacement()
                    .map(replacement -> replacement.preview().isBlank()
                            ? ""
                            : replacement.preview())
                    .orElseGet(() -> result == null ? "" : result.content());
            Preview preview = preview(content);
            ObjectNode node = object("tool_finished")
                    .put("toolUseId", event.toolUseId())
                    .put("toolName", event.toolName())
                    .put("status", event.error() ? "error" : "ok")
                    .put("summary", toolSummariesById.getOrDefault(event.toolUseId(), ""))
                    .put("preview", preview.text())
                    .put("truncated", preview.truncated())
                    .put("hiddenLines", preview.hiddenLines());
            event.replacement()
                    .map(replacement -> replacement.storageRef().id())
                    .ifPresentOrElse(storageRef -> node.put("storageRef", storageRef), () -> node.putNull("storageRef"));
            emit(node);
        }

        private void emitContextStats(AgentEvent.ContextStatsEvent event) {
            var stats = event.stats();
            long totalTokens = stats.accounting().totalTokens();
            String source = uiAccountingSource(stats.accounting().source());
            String warning = stats.warningLevel().name().toLowerCase(java.util.Locale.ROOT);
            long percent = Math.round(stats.utilization() * 100.0d);
            emit(object("context_stats")
                    .put("badge", "context " + percent + "% " + warning + " " + totalTokens + "/"
                            + stats.effectiveInput() + " " + source)
                    .put("totalTokens", totalTokens)
                    .put("effectiveInput", stats.effectiveInput())
                    .put("source", source)
                    .put("warning", warning));
        }

        private String uiAccountingSource(minicode.context.accounting.TokenAccountingSource source) {
            return switch (source) {
                case PROVIDER_USAGE -> "provider";
                case PROVIDER_USAGE_WITH_ESTIMATE -> "provider+estimate";
                case ESTIMATE_ONLY -> "estimate";
            };
        }

        private void emit(ObjectNode event) {
            try {
                writer.println(MAPPER.writeValueAsString(event));
                writer.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to encode UI event.", exception);
            }
        }
    }

    private record Preview(String text, boolean truncated, int hiddenLines) {
    }

    private static Preview preview(String content) {
        if (content.length() <= PREVIEW_CHARS) {
            return new Preview(content, false, 0);
        }
        String visible = content.substring(0, PREVIEW_CHARS);
        String hidden = content.substring(PREVIEW_CHARS);
        return new Preview(visible, true, Math.max(1, hidden.split("\\R", -1).length));
    }

    private static ObjectNode object(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    private static String summarizeToolInput(String toolName, JsonNode input) {
        return UiToolInputSummarizer.summarize(toolName, input);
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String value(JsonNode input, String field) {
        JsonNode value = input.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String field(String name, String value) {
        return value == null || value.isBlank() ? "" : name + "=" + oneLine(value);
    }

    private static String join(String... values) {
        return String.join(" ", java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
