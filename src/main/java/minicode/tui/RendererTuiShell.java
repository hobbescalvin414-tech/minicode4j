package minicode.tui;

import minicode.app.ApplicationServices;
import minicode.context.stats.ContextWarningLevel;
import minicode.core.event.AgentEvent;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;
import minicode.session.model.SessionEvent;
import minicode.session.model.SessionEventType;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.tui.input.LineTuiInput;
import minicode.tui.input.TuiInput;
import minicode.tui.input.TuiInputEvent;
import minicode.tui.render.InputState;
import minicode.tui.render.RenderFrame;
import minicode.tui.render.RenderState;
import minicode.tui.render.StatusState;
import minicode.tui.render.TranscriptBlock;
import minicode.tui.render.TuiRenderer;
import minicode.tui.terminal.TerminalScreen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RendererTuiShell {
    private static final int PERMISSION_DETAIL_LINES = 18;
    private static final int PERMISSION_DETAIL_LINE_CHARS = 180;

    private final ApplicationServices services;
    private final TuiInput input;
    private final TerminalScreen screen;
    private final TuiRenderer renderer;
    private final int maxSteps;
    private final Object lock = new Object();
    private final boolean realtimeEvents;
    private RenderState state;
    private Thread activeTurn;
    private PendingPermission pendingPermission;

    public RendererTuiShell(ApplicationServices services, LineInput input, TerminalScreen screen, int maxSteps) {
        this(services, new LineTuiInput(input), screen, maxSteps, null);
    }

    public RendererTuiShell(ApplicationServices services, LineInput input, TerminalScreen screen, int maxSteps,
                            RendererTuiBridge bridge) {
        this(services, new LineTuiInput(input), screen, maxSteps, bridge);
    }

    public RendererTuiShell(ApplicationServices services, TuiInput input, TerminalScreen screen, int maxSteps,
                            RendererTuiBridge bridge) {
        this.services = Objects.requireNonNull(services, "services");
        this.input = Objects.requireNonNull(input, "input");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.renderer = new TuiRenderer();
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        this.realtimeEvents = bridge != null;
        if (bridge != null) {
            bridge.attach(this);
        }
        this.state = RenderState.empty().withTranscript(projectSessionHistory());
        redraw();
    }

    public void runOnce() {
        try {
            runEvent(input.readEvent());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void runLoop() {
        try {
            while (true) {
                if (!runEvent(input.readEvent())) {
                    return;
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void awaitIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        Thread thread;
        synchronized (lock) {
            thread = activeTurn;
        }
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(1L, timeout.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for renderer turn", exception);
        }
    }

    void onAgentEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            switch (event) {
                case AgentEvent.AssistantMessageEvent assistantMessageEvent ->
                        projectMessage(assistantMessageEvent.message()).ifPresent(this::appendTranscriptLocked);
                case AgentEvent.ToolStartedEvent toolStartedEvent -> {
                    String summary = toolStartedEvent.toolName().equals("ask_user")
                            ? ""
                            : ToolInputSummarizer.summarize(toolStartedEvent.toolName(), toolStartedEvent.input());
                    appendTranscriptLocked(TranscriptBlock.toolStarted(
                            toolStartedEvent.toolUseId(), toolStartedEvent.toolName(), summary));
                    state = state.withStatus(StatusState.of("Running " + toolStartedEvent.toolName() + "..."))
                            .withInput(state.input().withMode(InputState.Mode.BUSY));
                }
                case AgentEvent.ToolFinishedEvent toolFinishedEvent -> {
                    String text = toolFinishedEvent.toolName().equals("ask_user")
                            ? ""
                            : toolFinishedEvent.replacement()
                            .map(record -> "<persisted-output originalChars=\"" + record.originalChars()
                                    + "\" storageRef=\"" + record.storageRef() + "\">\n"
                                    + record.preview() + "\n</persisted-output>")
                            .orElse("");
                    appendTranscriptLocked(TranscriptBlock.toolResult(toolFinishedEvent.toolUseId(),
                            toolFinishedEvent.toolName(), toolFinishedEvent.error(), text));
                    if (toolFinishedEvent.awaitUser()) {
                        state = state.withStatus(StatusState.of("Waiting for user answer..."))
                                .withInput(state.input().withMode(InputState.Mode.AWAITING_ASK_USER));
                    } else {
                        state = state.withStatus(StatusState.thinking())
                                .withInput(state.input().withMode(InputState.Mode.BUSY));
                    }
                }
                case AgentEvent.AwaitUserEvent awaitUserEvent -> {
                    appendTranscriptLocked(TranscriptBlock.askUser(awaitUserEvent.toolUseId(),
                            cleanAskUserQuestion(awaitUserEvent.question())));
                    state = state.withStatus(StatusState.of("Waiting for user answer..."))
                            .withInput(state.input().withMode(InputState.Mode.AWAITING_ASK_USER));
                }
                case AgentEvent.ContextStatsEvent contextStatsEvent -> state = state.withContextBadge(contextBadge(contextStatsEvent));
                case AgentEvent.TurnCancelledEvent cancelledEvent -> appendTranscriptLocked(TranscriptBlock.diagnostic(
                        "cancelled: source=" + cancelledEvent.cancellation().source()
                                + " phase=" + cancelledEvent.cancellation().phase()
                                + " reason=" + cancelledEvent.cancellation().reason()));
                case AgentEvent.AutoCompactEvent autoCompactEvent -> {
                    switch (autoCompactEvent.type()) {
                        case STARTED -> appendTranscriptLocked(TranscriptBlock.diagnostic("auto_compact: started"));
                        case COMPLETED -> autoCompactEvent.result()
                                .flatMap(minicode.context.compact.CompressionResult::boundary)
                                .ifPresent(boundary -> appendTranscriptLocked(TranscriptBlock.compact(
                                        "Auto compacted: " + boundary.metadata().tokensBefore()
                                                + " -> " + boundary.metadata().tokensAfter() + " tokens")));
                        case SKIPPED -> appendTranscriptLocked(TranscriptBlock.diagnostic("auto_compact: skipped "
                                + autoCompactEvent.reason().orElse("skipped")));
                        case FAILED -> appendTranscriptLocked(TranscriptBlock.diagnostic("auto_compact: failed "
                                + autoCompactEvent.reason().orElse("failed")));
                    }
                }
                case minicode.core.event.ToolResultsBudgetedEvent ignored -> {
                }
            }
            redrawLocked();
        }
    }

    PermissionPromptResult requestPermission(PermissionRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<PermissionPromptResult> future = new CompletableFuture<>();
        synchronized (lock) {
            pendingPermission = new PendingPermission(request, future, Optional.empty());
            appendTranscriptLocked(TranscriptBlock.permissionAudit(request.requestId(),
                    permissionPendingText(request)));
            state = state.withStatus(StatusState.of("Waiting for approval..."))
                    .withInput(InputState.of(InputState.Mode.PENDING_PERMISSION, "", 0));
            redrawLocked();
        }
        try {
            return future.get(365, TimeUnit.DAYS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                    "Permission prompt interrupted");
        } catch (Exception exception) {
            return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                    "Permission prompt failed: " + exception.getMessage());
        }
    }

    private boolean runEvent(TuiInputEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event.kind()) {
            case EOF -> false;
            case PAGE_UP, SCROLL_UP -> {
                scrollUp();
                yield true;
            }
            case PAGE_DOWN, SCROLL_DOWN -> {
                scrollDown();
                yield true;
            }
            case CURSOR_LEFT -> {
                moveCursorLeft();
                yield true;
            }
            case CURSOR_RIGHT -> {
                moveCursorRight();
                yield true;
            }
            case CHARACTER -> {
                appendInputCharacter(event.character().orElseThrow());
                yield true;
            }
            case BACKSPACE -> {
                backspaceInputCharacter();
                yield true;
            }
            case SUBMIT -> runLine(submittedText(event));
        };
    }

    private String submittedText(TuiInputEvent event) {
        if (event.text().isPresent()) {
            return event.text().orElseThrow();
        }
        synchronized (lock) {
            return state.input().text();
        }
    }

    private boolean runLine(String line) {
        if (line == null) {
            return false;
        }
        PendingPermission permissionToResolve;
        PermissionPromptResult permissionResult;
        synchronized (lock) {
            permissionToResolve = pendingPermission;
        }
        if (permissionToResolve != null) {
            permissionResult = handlePermissionInput(line);
            if (permissionResult != null) {
                permissionToResolve.future().complete(permissionResult);
            }
            return true;
        }

        String trimmed = line.trim();
        if (trimmed.isBlank()) {
            return true;
        }
        if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
            return false;
        }
        if (handleScrollCommand(trimmed)) {
            return true;
        }
        if ("/compact".equals(trimmed)) {
            runCompactCommand();
            return true;
        }
        synchronized (lock) {
            if (activeTurn != null && activeTurn.isAlive()) {
                appendTranscriptLocked(TranscriptBlock.diagnostic("busy: wait for the current turn to finish"));
                redrawLocked();
                return true;
            }
        }
        boolean answerMode;
        synchronized (lock) {
            answerMode = state.input().mode() == InputState.Mode.AWAITING_ASK_USER;
        }
        startTurn(line, answerMode);
        return true;
    }

    private void appendInputCharacter(char value) {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY) {
                return;
            }
            String text = current.text();
            int cursor = current.cursor();
            String next = text.substring(0, cursor) + value + text.substring(cursor);
            state = state.withInput(InputState.of(current.mode(), next, cursor + 1));
            redrawLocked();
        }
    }

    private void backspaceInputCharacter() {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY || current.cursor() == 0) {
                return;
            }
            String text = current.text();
            int cursor = current.cursor();
            String next = text.substring(0, cursor - 1) + text.substring(cursor);
            state = state.withInput(InputState.of(current.mode(), next, cursor - 1));
            redrawLocked();
        }
    }

    private void moveCursorLeft() {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY || current.cursor() == 0) {
                return;
            }
            state = state.withInput(InputState.of(current.mode(), current.text(), current.cursor() - 1));
            redrawLocked();
        }
    }

    private void moveCursorRight() {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY || current.cursor() >= current.text().length()) {
                return;
            }
            state = state.withInput(InputState.of(current.mode(), current.text(), current.cursor() + 1));
            redrawLocked();
        }
    }

    private boolean handleScrollCommand(String trimmed) {
        synchronized (lock) {
            switch (trimmed.toLowerCase(Locale.ROOT)) {
                case "/scroll-up", "/pageup" -> {
                    scrollUpLocked();
                    return true;
                }
                case "/scroll-down", "/pagedown" -> {
                    scrollDownLocked();
                    return true;
                }
                case "/bottom" -> {
                    state = state.withScrollOffset(0);
                    redrawLocked();
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
    }

    private void scrollUp() {
        synchronized (lock) {
            scrollUpLocked();
        }
    }

    private void scrollDown() {
        synchronized (lock) {
            scrollDownLocked();
        }
    }

    private void scrollUpLocked() {
        int page = Math.max(1, screen.size().rows() - 4);
        state = state.withScrollOffset(state.scrollOffset() + page);
        redrawLocked();
    }

    private void scrollDownLocked() {
        int page = Math.max(1, screen.size().rows() - 4);
        state = state.withScrollOffset(Math.max(0, state.scrollOffset() - page));
        redrawLocked();
    }

    private PermissionPromptResult handlePermissionInput(String line) {
        synchronized (lock) {
            if (pendingPermission == null) {
                return null;
            }
            PendingPermission pending = pendingPermission;
            if (pending.feedbackChoice().isPresent()) {
                PermissionChoice choice = pending.feedbackChoice().orElseThrow();
                String feedback = line == null || line.isBlank() ? "Permission denied by user" : line;
                PermissionPromptResult result = PermissionPromptResult.deny(choice.key(), choice.decision(), feedback);
                appendTranscriptLocked(TranscriptBlock.permissionAudit(pending.request().requestId(),
                        "denied " + choice.key() + " feedback=" + oneLine(feedback)));
                pendingPermission = null;
                state = state.withStatus(StatusState.thinking())
                        .withInput(InputState.of(InputState.Mode.BUSY, "", 0));
                redrawLocked();
                return result;
            }
            Optional<PermissionChoice> selected = selectChoice(pending.request(), line);
            if (selected.isEmpty()) {
                appendTranscriptLocked(TranscriptBlock.permissionAudit(pending.request().requestId(),
                        "unknown choice " + line.trim() + "\n" + permissionChoicesText(pending.request())));
                redrawLocked();
                return null;
            }
            PermissionChoice choice = selected.orElseThrow();
            if (choice.requiresFeedback()) {
                pendingPermission = new PendingPermission(pending.request(), pending.future(), Optional.of(choice));
                state = state.withStatus(StatusState.of("Permission feedback..."))
                        .withInput(InputState.of(InputState.Mode.PERMISSION_FEEDBACK, "", 0));
                redrawLocked();
                return null;
            }
            PermissionPromptResult result = isAllow(choice.decision())
                    ? PermissionPromptResult.allow(choice.key(), choice.decision())
                    : PermissionPromptResult.deny(choice.key(), choice.decision(), null);
            appendTranscriptLocked(TranscriptBlock.permissionAudit(pending.request().requestId(),
                    (result.allowed() ? "allowed " : "denied ") + choice.key()));
            pendingPermission = null;
            state = state.withStatus(StatusState.thinking())
                    .withInput(InputState.of(InputState.Mode.BUSY, "", 0));
            redrawLocked();
            return result;
        }
    }

    private void startTurn(String line, boolean answerMode) {
        List<ChatMessage> history = services.sessionMessages();
        UserMessage userMessage = new UserMessage(line);
        synchronized (lock) {
            appendTranscriptLocked(answerMode
                    ? TranscriptBlock.userAnswer(userMessage.content())
                    : TranscriptBlock.user(userMessage.content()));
            state = state.withInput(InputState.of(InputState.Mode.BUSY, "", 0))
                    .withStatus(StatusState.thinking());
            redrawLocked();
        }
        services.sessionPersistenceRunner().apply(new TurnPersistencePlan(
                List.of(new PersistenceAction.AppendMessagesAction(List.of(userMessage)))
        ));
        List<ChatMessage> turnMessages = new ArrayList<>(history);
        turnMessages.add(userMessage);
        Thread thread = new Thread(() -> runTurnInBackground(turnMessages), "minicode-renderer-turn");
        synchronized (lock) {
            activeTurn = thread;
        }
        thread.start();
    }

    private void runTurnInBackground(List<ChatMessage> turnMessages) {
        AgentTurnResult result = null;
        try {
            result = services.runTurn(services.turnRequest(List.copyOf(turnMessages), maxSteps));
            services.sessionPersistenceRunner().apply(result.persistencePlan());
            synchronized (lock) {
                if (!realtimeEvents) {
                    state = state.appendTranscript(projectPersistencePlan(result.persistencePlan()));
                }
                appendStopReasonLocked(result);
                redrawLocked();
            }
        } finally {
            synchronized (lock) {
                if (Thread.currentThread() == activeTurn) {
                    activeTurn = null;
                }
                if (result == null) {
                    state = state.clearStatus().withInput(InputState.empty());
                    redrawLocked();
                }
            }
        }
    }

    private void runCompactCommand() {
        appendDiagnostic("compact: started");
        minicode.context.compact.ManualCompactResult result = services.manualCompact();
        switch (result.status()) {
            case COMPACTED -> {
                var metadata = result.boundary().orElseThrow().metadata();
                appendDiagnostic("compact: ok compressed=" + metadata.messagesCompressed()
                        + " tokens=" + metadata.tokensBefore() + "->" + metadata.tokensAfter());
            }
            case SKIPPED -> appendDiagnostic("compact: skipped " + result.reason().orElse("nothing to compact"));
            case FAILED -> appendDiagnostic("compact: failed " + result.reason().orElse("summary generation failed"));
        }
    }

    private void appendDiagnostic(String text) {
        synchronized (lock) {
            appendTranscriptLocked(TranscriptBlock.diagnostic(text));
            redrawLocked();
        }
    }

    private void appendStopReasonLocked(AgentTurnResult result) {
        AgentTurnStopReason stopReason = result.stopReason();
        if (stopReason == AgentTurnStopReason.AWAIT_USER) {
            state = state.withStatus(StatusState.of("Waiting for user answer..."))
                    .withInput(InputState.of(InputState.Mode.AWAITING_ASK_USER, "", 0));
            return;
        }
        state = state.clearStatus().withInput(InputState.empty());
        if (stopReason == AgentTurnStopReason.FINAL) {
            return;
        }
        appendTranscriptLocked(TranscriptBlock.diagnostic("turn_stop: " + stopReason));
        switch (stopReason) {
            case MAX_STEPS -> appendTranscriptLocked(TranscriptBlock.diagnostic(
                    "max_steps: reached max steps. Type \"continue\" or \"继续\" to keep going from the current context."));
            case MODEL_ERROR -> appendTranscriptLocked(TranscriptBlock.diagnostic("model_error: "
                    + result.stopDetails().orElseThrow()));
            case CANCELLED -> appendTranscriptLocked(TranscriptBlock.diagnostic("cancelled: "
                    + result.stopDetails().orElseThrow()));
            case EMPTY_RESPONSE_FALLBACK -> appendTranscriptLocked(TranscriptBlock.diagnostic("empty_response_fallback"));
            case FINAL, AWAIT_USER -> {
            }
        }
    }

    private List<TranscriptBlock> projectSessionHistory() {
        List<TranscriptBlock> blocks = new ArrayList<>();
        for (SessionEvent event : services.sessionStore().readAll(services.sessionId(), services.cwd().toString())) {
            if (event.type() == SessionEventType.COMPACT_BOUNDARY) {
                blocks.add(TranscriptBlock.compact(event.compactMetadata()
                        .map(metadata -> "Context compacted: " + metadata.tokensBefore()
                                + " -> " + metadata.tokensAfter() + " tokens")
                        .orElse("Context compacted")));
                continue;
            }
            event.message().flatMap(RendererTuiShell::projectMessage).ifPresent(blocks::add);
        }
        return List.copyOf(blocks);
    }

    private static List<TranscriptBlock> projectPersistencePlan(TurnPersistencePlan plan) {
        ArrayList<TranscriptBlock> blocks = new ArrayList<>();
        for (PersistenceAction action : plan.actions()) {
            if (action instanceof PersistenceAction.AppendMessagesAction appendMessagesAction) {
                for (ChatMessage message : appendMessagesAction.messages()) {
                    projectMessage(message).ifPresent(blocks::add);
                }
            }
        }
        return blocks;
    }

    private static Optional<TranscriptBlock> projectMessage(ChatMessage message) {
        return switch (message) {
            case UserMessage userMessage -> Optional.of(TranscriptBlock.user(userMessage.content()));
            case AssistantMessage assistantMessage -> Optional.of(TranscriptBlock.assistant(assistantMessage.content()));
            case AssistantProgressMessage progressMessage -> Optional.of(TranscriptBlock.progress(progressMessage.content()));
            case AssistantToolCallMessage toolCallMessage -> Optional.of(TranscriptBlock.toolStarted(
                    toolCallMessage.toolUseId(), toolCallMessage.toolName(),
                    toolCallMessage.toolName().equals("ask_user")
                            ? ""
                            : ToolInputSummarizer.summarize(toolCallMessage.toolName(), toolCallMessage.input())));
            case ToolResultMessage toolResultMessage -> Optional.of(TranscriptBlock.toolResult(
                    toolResultMessage.toolUseId(), toolResultMessage.toolName(),
                    toolResultMessage.error(), toolResultMessage.toolName().equals("ask_user")
                            ? ""
                            : toolResultMessage.content()));
            case ContextSummaryMessage summary -> Optional.of(TranscriptBlock.compact(summary.content()));
            case SystemMessage ignored -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private void appendTranscriptLocked(TranscriptBlock block) {
        state = state.appendTranscript(block);
    }

    private void redraw() {
        synchronized (lock) {
            redrawLocked();
        }
    }

    private void redrawLocked() {
        RenderFrame frame = renderer.render(state, screen.size());
        screen.redraw(frame);
    }

    private static Optional<PermissionChoice> selectChoice(PermissionRequest request, String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            int choiceNumber = Integer.parseInt(normalized);
            if (choiceNumber >= 1 && choiceNumber <= request.choices().size()) {
                return Optional.of(request.choices().get(choiceNumber - 1));
            }
        } catch (NumberFormatException ignored) {
            // Continue with key/label matching.
        }
        return request.choices().stream()
                .filter(choice -> normalize(choice.key()).equals(normalized)
                        || normalize(choice.label()).equals(normalized))
                .findFirst();
    }

    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }

    private static String permissionPendingText(PermissionRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("pending ").append(request.details().title());
        String body = oneLine(request.details().body());
        if (!body.isBlank()) {
            builder.append('\n').append(truncate(body, PERMISSION_DETAIL_LINE_CHARS));
        }
        appendPermissionDetails(builder, request.details().facts());
        builder.append('\n').append(permissionChoicesText(request));
        return builder.toString();
    }

    private static void appendPermissionDetails(StringBuilder builder, List<String> facts) {
        int appended = 0;
        for (String fact : facts) {
            if (appended >= PERMISSION_DETAIL_LINES) {
                int remaining = facts.size() - appended;
                builder.append('\n').append("... truncated permission details ").append(remaining).append(" lines");
                return;
            }
            String text = sanitizePermissionDetail(fact);
            if (!text.isBlank()) {
                builder.append('\n').append(truncate(text, PERMISSION_DETAIL_LINE_CHARS));
                appended++;
            }
        }
    }

    private static String permissionChoicesText(PermissionRequest request) {
        StringBuilder builder = new StringBuilder("choices:");
        List<PermissionChoice> choices = request.choices();
        for (int index = 0; index < choices.size(); index++) {
            PermissionChoice choice = choices.get(index);
            builder.append('\n')
                    .append('[').append(index + 1).append("] ")
                    .append(choice.key())
                    .append(" - ")
                    .append(choice.label());
        }
        return builder.toString();
    }

    private static String sanitizePermissionDetail(String value) {
        return value == null ? "" : value.replace("\r", "").stripTrailing();
    }

    private static String contextBadge(AgentEvent.ContextStatsEvent event) {
        double percent = event.stats().utilization() * 100.0d;
        return "context " + Math.round(percent) + "% "
                + warningLevelLabel(event.stats().warningLevel()) + " "
                + compactTokenCount(event.stats().accounting().totalTokens()) + "/"
                + compactTokenCount(event.stats().effectiveInput()) + " "
                + accountingSourceLabel(event.stats().accounting().source());
    }

    private static String warningLevelLabel(ContextWarningLevel level) {
        return switch (level) {
            case OK, NORMAL -> "normal";
            case WARNING -> "warning";
            case CRITICAL -> "critical";
            case BLOCKED -> "blocked";
        };
    }

    private static String accountingSourceLabel(minicode.context.accounting.TokenAccountingSource source) {
        return switch (source) {
            case PROVIDER_USAGE -> "provider";
            case PROVIDER_USAGE_WITH_ESTIMATE -> "provider+estimate";
            case ESTIMATE_ONLY -> "estimate";
        };
    }

    private static String compactTokenCount(long tokens) {
        if (tokens >= 1_000_000L) {
            double millions = tokens / 1_000_000.0d;
            return String.format(Locale.ROOT, "%.1fM", millions);
        }
        if (tokens >= 1_000L) {
            long thousands = Math.round(tokens / 1_000.0d);
            return thousands + "k";
        }
        return Long.toString(tokens);
    }

    private static String oneLine(String value) {
        String collapsed = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 180) {
            return collapsed;
        }
        return collapsed.substring(0, 180) + "...";
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    private static String cleanAskUserQuestion(String question) {
        String value = Objects.requireNonNull(question, "question").trim();
        String prefix = "Question for user:";
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length()).trim();
        }
        return value;
    }

    private record PendingPermission(PermissionRequest request, CompletableFuture<PermissionPromptResult> future,
                                     Optional<PermissionChoice> feedbackChoice) {
        private PendingPermission {
            request = Objects.requireNonNull(request, "request");
            future = Objects.requireNonNull(future, "future");
            feedbackChoice = Objects.requireNonNull(feedbackChoice, "feedbackChoice");
        }
    }
}
