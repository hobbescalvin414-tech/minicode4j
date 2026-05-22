package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.app.ApplicationServices;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEvent;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;
import minicode.core.loop.ModelAdapter;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.service.SessionService;
import minicode.session.store.SessionStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UiStdioRealBackend {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_STEPS = 32;

    public enum Mode {
        FAKE_ONLY_SKELETON,
        REAL
    }

    private final Mode mode;
    private final RuntimeConfig runtimeConfig;
    private ModelAdapter modelAdapterOverride;

    private UiStdioRealBackend(Mode mode, RuntimeConfig runtimeConfig) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.runtimeConfig = runtimeConfig;
    }

    public static UiStdioRealBackend fakeOnlySkeleton() {
        return new UiStdioRealBackend(Mode.FAKE_ONLY_SKELETON, null);
    }

    public static UiStdioRealBackend real(RuntimeConfig runtimeConfig) {
        return new UiStdioRealBackend(Mode.REAL, Objects.requireNonNull(runtimeConfig, "runtimeConfig"));
    }

    static UiStdioRealBackend real(RuntimeConfig runtimeConfig, ModelAdapter modelAdapter) {
        UiStdioRealBackend backend = real(runtimeConfig);
        backend.modelAdapterOverride = Objects.requireNonNull(modelAdapter, "modelAdapter");
        return backend;
    }

    public Mode mode() {
        return mode;
    }

    public boolean loadsRuntimeConfig() {
        return mode == Mode.REAL;
    }

    public boolean readsApiKeys() {
        return mode == Mode.REAL;
    }

    public boolean executesTools() {
        return mode == Mode.REAL;
    }

    public void run(Path defaultHome, Path defaultCwd, InputStream input, OutputStream output, int defaultMaxSteps) {
        if (mode != Mode.REAL) {
            throw new IllegalStateException("UiStdioRealBackend fake skeleton cannot run.");
        }
        Session session = new Session(
                Objects.requireNonNull(defaultHome, "defaultHome").toAbsolutePath().normalize(),
                Objects.requireNonNull(defaultCwd, "defaultCwd").toAbsolutePath().normalize(),
                UUID.randomUUID().toString(),
                defaultMaxSteps > 0 ? defaultMaxSteps : DEFAULT_MAX_STEPS
        );
        Emitter emitter = new Emitter(output);
        UiAgentEventProjector projector = new UiAgentEventProjector();
        UiAskUserFlow askUserFlow = new UiAskUserFlow();
        RealPermissionPromptHandler permissionHandler = new RealPermissionPromptHandler(emitter);
        ExecutorService turnExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minicode-ui-stdio-turn");
            thread.setDaemon(true);
            return thread;
        });
        ApplicationServices services = null;
        CompletableFuture<Void> activeTurn = null;
        boolean running = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (activeTurn != null && activeTurn.isDone()) {
                    waitForTurn(activeTurn, permissionHandler);
                    running = false;
                }
                JsonNode command = MAPPER.readTree(line);
                String type = command.path("type").asText("");
                switch (type) {
                    case "init" -> {
                        waitForTurn(activeTurn, permissionHandler);
                        if (services != null) {
                            services.close();
                        }
                        Session requestedSession = session.fromInit(command);
                        if (command.hasNonNull("resumeSessionId")
                                && !validateResumeSession(requestedSession, emitter)) {
                            return;
                        }
                        session = requestedSession;
                        services = createServices(session, permissionHandler, event ->
                                projectEvent(event, projector, askUserFlow, emitter));
                        emitter.emit(new UiEvent.Ready(session.sessionId(), session.cwd().toString(), runtimeConfig.model()));
                        emitHistory(services, session, emitter);
                    }
                    case "user_submit" -> {
                        if (running) {
                            emitter.emit(new UiEvent.Error("Backend is busy.", true));
                            continue;
                        }
                        String text = command.path("text").asText("");
                        if (text.isBlank()) {
                            emitter.emit(new UiEvent.Error("user_submit text must not be blank.", true));
                            continue;
                        }
                        if (services == null) {
                            services = createServices(session, permissionHandler, event ->
                                    projectEvent(event, projector, askUserFlow, emitter));
                            emitter.emit(new UiEvent.Ready(session.sessionId(), session.cwd().toString(), runtimeConfig.model()));
                            emitHistory(services, session, emitter);
                        }
                        ApplicationServices actualServices = services;
                        Session actualSession = session;
                        activeTurn = CompletableFuture.runAsync(() ->
                                runUserTurn(text, actualServices, actualSession, emitter), turnExecutor);
                        running = true;
                    }
                    case "permission_response" -> permissionHandler.handleResponse(
                            command.path("requestId").asText(""),
                            command.path("choiceKey").asText(""),
                            optionalText(command, "feedback"));
                    case "ask_user_answer" -> {
                        if (running) {
                            waitForTurn(activeTurn, permissionHandler);
                            running = false;
                        }
                        UiAskUserFlow.AnswerResult result = askUserFlow.handleAnswer(
                                command.path("toolUseId").asText(""),
                                command.path("text").asText(""));
                        if (result.error().isPresent()) {
                            emitter.emit(result.error().orElseThrow());
                            continue;
                        }
                        if (services == null) {
                            emitter.emit(new UiEvent.Error("Backend is not initialized.", true));
                            continue;
                        }
                        UiAskUserFlow.Answer answer = result.answer().orElseThrow();
                        ApplicationServices actualServices = services;
                        Session actualSession = session;
                        activeTurn = CompletableFuture.runAsync(() ->
                                runUserTurn(answer.text(), actualServices, actualSession, emitter), turnExecutor);
                        running = true;
                    }
                    case "shutdown" -> {
                        permissionHandler.shutdown();
                        waitForTurn(activeTurn, permissionHandler);
                        return;
                    }
                    default -> emitter.emit(new UiEvent.Error("Unsupported UI command: " + type, true));
                }
                if (activeTurn != null && activeTurn.isDone()) {
                    waitForTurn(activeTurn, permissionHandler);
                    running = false;
                }
            }
        } catch (IOException exception) {
            emitter.emit(new UiEvent.Error("UI stdio failure: " + safeMessage(exception), false));
        } catch (RuntimeException exception) {
            emitter.emit(new UiEvent.Error("UI backend failure: " + safeMessage(exception), false));
        } finally {
            permissionHandler.shutdown();
            waitForTurn(activeTurn, permissionHandler);
            turnExecutor.shutdownNow();
            if (services != null) {
                services.close();
            }
            emitter.flush();
        }
    }

    private static boolean validateResumeSession(Session session, Emitter emitter) {
        try {
            new SessionService(new SessionStore(session.home().resolve("sessions")))
                    .requireResumable(session.cwd().toString(), session.sessionId());
            return true;
        } catch (IllegalArgumentException exception) {
            emitter.emit(new UiEvent.Error(safeMessage(exception), false));
            return false;
        }
    }

    private ApplicationServices createServices(Session session, PermissionPromptHandler permissionPromptHandler,
                                               minicode.core.event.AgentEventSink eventSink) {
        if (modelAdapterOverride != null) {
            return ApplicationServices.create(
                    session.home(),
                    session.cwd(),
                    session.sessionId(),
                    runtimeConfig,
                    modelAdapterOverride,
                    eventSink,
                    permissionPromptHandler
            );
        }
        return ApplicationServices.create(
                session.home(),
                session.cwd(),
                session.sessionId(),
                runtimeConfig,
                eventSink,
                permissionPromptHandler
        );
    }

    private static void emitHistory(ApplicationServices services, Session session, Emitter emitter) {
        UiHistoryProjector historyProjector = new UiHistoryProjector();
        services.sessionStore().readAll(session.sessionId(), session.cwd().toString()).stream()
                .map(List::of)
                .flatMap(events -> historyProjector.project(events).stream())
                .forEach(emitter::emit);
    }

    private static void projectEvent(AgentEvent event, UiAgentEventProjector projector,
                                     UiAskUserFlow askUserFlow, Emitter emitter) {
        if (event instanceof AgentEvent.AwaitUserEvent awaitUser) {
            emitter.emit(askUserFlow.start(awaitUser.toolUseId(), stripAskUserPrefix(awaitUser.question())));
            return;
        }
        for (UiEvent uiEvent : projector.project(event)) {
            emitter.emit(uiEvent);
        }
    }

    private static void runUserTurn(String text, ApplicationServices services, Session session, Emitter emitter) {
        try {
            emitter.emit(new UiEvent.Status("Thinking...", true));
            List<ChatMessage> history = services.sessionMessages();
            UserMessage userMessage = new UserMessage(text);
            services.sessionPersistenceRunner().apply(new TurnPersistencePlan(
                    List.of(new PersistenceAction.AppendMessagesAction(List.of(userMessage)))
            ));
            AgentTurnResult result = services.runTurn(services.turnRequest(
                    appendUserMessage(history, userMessage),
                    session.maxSteps()
            ));
            services.sessionPersistenceRunner().apply(result.persistencePlan());
            emitter.emit(turnStop(result));
            if (result.stopReason() != minicode.core.turn.AgentTurnStopReason.AWAIT_USER) {
                emitter.emit(new UiEvent.Status("Ready", false));
            }
        } catch (RuntimeException exception) {
            emitter.emit(new UiEvent.Error("Turn failed: " + safeMessage(exception), false));
        }
    }

    private static UiEvent.TurnStop turnStop(AgentTurnResult result) {
        return new UiEvent.TurnStop(result.stopReason().name(), stopMessage(result));
    }

    private static Optional<String> stopMessage(AgentTurnResult result) {
        return switch (result.stopReason()) {
            case FINAL, AWAIT_USER -> Optional.empty();
            case MAX_STEPS -> Optional.of("Type continue to keep going from the current context.");
            case MODEL_ERROR -> result.stopDetails()
                    .filter(ModelErrorDetails.class::isInstance)
                    .map(ModelErrorDetails.class::cast)
                    .map(details -> UiSafeText.redact(details.error().message()));
            case CANCELLED -> result.stopDetails()
                    .filter(CancellationDetails.class::isInstance)
                    .map(CancellationDetails.class::cast)
                    .map(details -> UiSafeText.redact(details.cancellation().reason()));
            case EMPTY_RESPONSE_FALLBACK -> result.stopDetails()
                    .filter(EmptyFallbackDetails.class::isInstance)
                    .map(EmptyFallbackDetails.class::cast)
                    .flatMap(EmptyFallbackDetails::reason)
                    .map(UiSafeText::redact);
        };
    }

    private static List<ChatMessage> appendUserMessage(List<ChatMessage> existingMessages, UserMessage userMessage) {
        java.util.ArrayList<ChatMessage> messages = new java.util.ArrayList<>(existingMessages);
        messages.add(userMessage);
        return List.copyOf(messages);
    }

    private static void waitForTurn(CompletableFuture<Void> activeTurn,
                                    RealPermissionPromptHandler permissionHandler) {
        if (activeTurn == null) {
            return;
        }
        try {
            activeTurn.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            permissionHandler.shutdown();
        } catch (ExecutionException ignored) {
            // The turn runner emits a UI error before propagating failures.
        }
    }

    private static Optional<String> optionalText(JsonNode command, String field) {
        return command.has(field) && !command.get(field).isNull()
                ? Optional.of(command.get(field).asText())
                : Optional.empty();
    }

    private static String stripAskUserPrefix(String question) {
        return question.startsWith("Question for user:")
                ? question.substring("Question for user:".length()).trim()
                : question;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return UiSafeText.redact(message);
    }

    private record Session(Path home, Path cwd, String sessionId, int maxSteps) {
        private Session fromInit(JsonNode command) {
            Path actualHome = command.hasNonNull("home")
                    ? Path.of(command.get("home").asText()).toAbsolutePath().normalize()
                    : home;
            Path actualCwd = command.hasNonNull("cwd")
                    ? Path.of(command.get("cwd").asText()).toAbsolutePath().normalize()
                    : cwd;
            String actualSessionId = command.hasNonNull("resumeSessionId")
                    ? command.get("resumeSessionId").asText()
                    : command.hasNonNull("sessionId")
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

    private static final class RealPermissionPromptHandler implements PermissionPromptHandler {
        private final UiPermissionBridge bridge = new UiPermissionBridge();
        private final Emitter emitter;
        private CompletableFuture<PermissionPromptResult> pendingResult;

        private RealPermissionPromptHandler(Emitter emitter) {
            this.emitter = Objects.requireNonNull(emitter, "emitter");
        }

        @Override
        public PermissionPromptResult prompt(PermissionRequest request) {
            CompletableFuture<PermissionPromptResult> future;
            synchronized (this) {
                if (pendingResult != null) {
                    return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                            "Another permission request is already pending.");
                }
                pendingResult = new CompletableFuture<>();
                future = pendingResult;
                emitter.emit(bridge.start(request));
            }
            try {
                return future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                        "Permission prompt interrupted.");
            } catch (ExecutionException exception) {
                return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                        "Permission prompt failed.");
            } finally {
                synchronized (this) {
                    if (pendingResult == future) {
                        pendingResult = null;
                    }
                }
            }
        }

        private synchronized void handleResponse(String requestId, String choiceKey, Optional<String> feedback) {
            UiPermissionBridge.ResponseResult result = bridge.handleResponse(requestId, choiceKey, feedback);
            result.error().ifPresent(emitter::emit);
            result.audit().ifPresent(emitter::emit);
            if (result.promptResult().isPresent() && pendingResult != null) {
                CompletableFuture<PermissionPromptResult> future = pendingResult;
                pendingResult = null;
                future.complete(result.promptResult().orElseThrow());
            }
        }

        private synchronized void shutdown() {
            if (pendingResult != null) {
                CompletableFuture<PermissionPromptResult> future = pendingResult;
                pendingResult = null;
                bridge.clearFatal("Backend shutdown.");
                future.complete(PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                        "Backend shutdown."));
            }
        }
    }

    private static final class Emitter {
        private final PrintWriter writer;
        private final UiEventEncoder encoder = new UiEventEncoder();

        private Emitter(OutputStream output) {
            writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
        }

        private synchronized void emit(UiEvent event) {
            writer.println(encoder.encode(event));
            writer.flush();
        }

        private synchronized void flush() {
            writer.flush();
        }
    }
}
