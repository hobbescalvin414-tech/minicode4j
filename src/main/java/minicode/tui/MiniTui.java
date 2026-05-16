package minicode.tui;

import minicode.app.ApplicationServices;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class MiniTui {
    public static final int DEFAULT_MAX_STEPS = 32;

    private final ApplicationServices services;
    private final LineInput input;
    private final PrintWriter output;
    private final int maxSteps;

    public MiniTui(ApplicationServices services, InputStream input, OutputStream output) {
        this(services, new BufferedLineInput(new BufferedReader(new InputStreamReader(Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8))),
                output, DEFAULT_MAX_STEPS);
    }

    public MiniTui(ApplicationServices services, InputStream input, OutputStream output, int maxSteps) {
        this(services, new BufferedLineInput(new BufferedReader(new InputStreamReader(Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8))),
                output, maxSteps);
    }

    public MiniTui(ApplicationServices services, BufferedReader input, OutputStream output) {
        this(services, new BufferedLineInput(input), output, DEFAULT_MAX_STEPS);
    }

    public MiniTui(ApplicationServices services, BufferedReader input, OutputStream output, int maxSteps) {
        this(services, new BufferedLineInput(input), output, maxSteps);
    }

    public MiniTui(ApplicationServices services, LineInput input, OutputStream output, int maxSteps) {
        this.services = Objects.requireNonNull(services, "services");
        this.input = Objects.requireNonNull(input, "input");
        this.output = new PrintWriter(Objects.requireNonNull(output, "output"), true, StandardCharsets.UTF_8);
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }

    public void runOnce() {
        try {
            String line = input.readLine();
            runLine(line);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void runLoop() {
        try {
            String line;
            while ((line = input.readLine()) != null) {
                if (!runLine(line)) {
                    return;
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean runLine(String line) {
        if (line == null) {
            return false;
        }
        if (line.isBlank()) {
            return true;
        }
        String trimmed = line.trim();
        if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
            return false;
        }
        if ("/compact".equals(trimmed)) {
            runCompactCommand();
            return true;
        }

        List<ChatMessage> history = services.sessionMessages();
        UserMessage userMessage = new UserMessage(line);
        output.println("user: " + userMessage.content());
        services.sessionPersistenceRunner().apply(new TurnPersistencePlan(
                List.of(new PersistenceAction.AppendMessagesAction(List.of(userMessage)))
        ));

        List<ChatMessage> turnMessages = new java.util.ArrayList<>(history);
        turnMessages.add(userMessage);
        AgentTurnResult result = services.runTurn(services.turnRequest(List.copyOf(turnMessages), maxSteps));
        services.sessionPersistenceRunner().apply(result.persistencePlan());
        renderTurnResult(result);
        return true;
    }

    private void runCompactCommand() {
        output.println("compact: started");
        minicode.context.compact.ManualCompactResult result = services.manualCompact();
        switch (result.status()) {
            case COMPACTED -> {
                var metadata = result.boundary().orElseThrow().metadata();
                output.println("compact: ok compressed=" + metadata.messagesCompressed()
                        + " tokens=" + metadata.tokensBefore() + "->" + metadata.tokensAfter());
            }
            case SKIPPED -> output.println("compact: skipped " + result.reason().orElse("nothing to compact"));
            case FAILED -> output.println("compact: failed " + result.reason().orElse("summary generation failed"));
        }
    }

    private void renderTurnResult(AgentTurnResult result) {
        switch (result.stopReason()) {
            case FINAL -> {
            }
            case AWAIT_USER -> {
                output.println("turn_stop: " + result.stopReason());
                output.println("await_user: waiting for user input");
            }
            case MAX_STEPS -> {
                output.println("turn_stop: " + result.stopReason());
                output.println("max_steps: reached max steps. Type \"continue\" or \"继续\" to keep going from the current context.");
            }
            case MODEL_ERROR -> {
                output.println("turn_stop: " + result.stopReason());
                ModelErrorDetails details = (ModelErrorDetails) result.stopDetails().orElseThrow();
                output.println("model_error: " + details.error().message());
                output.println("model_error_details: retryable=" + details.error().retryable()
                        + details.error().diagnostics().map(value -> " diagnostics=" + value).orElse(""));
                renderModelErrorAdvice(details);
            }
            case CANCELLED -> {
                output.println("turn_stop: " + result.stopReason());
                CancellationDetails details = (CancellationDetails) result.stopDetails().orElseThrow();
                output.println("cancelled: source=" + details.cancellation().source()
                        + " phase=" + details.cancellation().phase()
                        + " reason=" + details.cancellation().reason());
            }
            case EMPTY_RESPONSE_FALLBACK -> {
                output.println("turn_stop: " + result.stopReason());
                result.stopDetails()
                        .map(EmptyFallbackDetails.class::cast)
                        .ifPresentOrElse(details -> output.println("empty_response_fallback: reason="
                                        + details.reason().orElse("unknown")
                                        + details.diagnostics().map(value -> " diagnostics=" + value).orElse("")),
                                () -> output.println("empty_response_fallback: reason=unknown"));
            }
        }
    }

    private void renderModelErrorAdvice(ModelErrorDetails details) {
        String diagnostics = details.error().diagnostics().orElse("");
        if (!isStatus400(diagnostics)) {
            return;
        }
        output.println("model_error_hint: provider rejected the request with status 400. Check the latest request shape, tool history, and provider-compatible payload rules.");
        if (diagnostics.toLowerCase(Locale.ROOT).contains("recenttoolhistory=true")) {
            output.println("model_error_hint: Recent history contains tool calls/results; this session may contain provider-incompatible tool history.");
            output.println("model_error_hint: Start a new session, or resume/fork before the failing turn after the issue is fixed.");
        }
    }

    private static boolean isStatus400(String diagnostics) {
        String normalized = diagnostics.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.contains("statuscode=400") || normalized.contains("status=400");
    }
}
