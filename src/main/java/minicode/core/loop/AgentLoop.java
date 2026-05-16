package minicode.core.loop;

import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactEventType;
import minicode.context.compact.AutoCompactResult;
import minicode.context.compact.CompactStatus;
import minicode.context.manager.ContextManager;
import minicode.context.stats.ContextStats;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.event.ToolResultsBudgetedEvent;
import minicode.core.message.*;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantStep;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.*;
import minicode.model.UsageStaleness;
import minicode.model.ModelRequestException;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ToolExecutor;
import minicode.tools.result.ToolResult;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AgentLoop {
    private static final String EMPTY_RESPONSE_MESSAGE =
            "The model returned an empty response after retries. Please try again.";
    private static final String PROGRESS_CONTINUATION_PROMPT =
            "Continue immediately from your <progress> update with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_CONTINUATION_PROMPT =
            "Your last response was empty. Continue immediately with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_AFTER_TOOL_RESULT_CONTINUATION_PROMPT =
            "Your last response was empty after recent tool results. Continue immediately by trying the next concrete step, adapting to any tool errors, or giving an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_AFTER_TOOL_ERROR_CONTINUATION_PROMPT =
            "Your last response was empty after recent tool results that included errors. Adapt to the tool error, try the next concrete step, or give an explicit <final> answer only if the task is complete.";
    private static final String MAX_TOKENS_THINKING_CONTINUATION_PROMPT =
            "Your previous response hit max_tokens during thinking before producing the next actionable step. Resume immediately and continue with the next concrete tool call, code change, or an explicit <final> answer only if the task is complete. Do not repeat the earlier plan.";
    private static final String PAUSE_TURN_THINKING_CONTINUATION_PROMPT =
            "Resume from the previous pause_turn and continue the task immediately. Produce the next concrete tool call, code change, or an explicit <final> answer only if the task is complete.";
    private static final int MODEL_REQUEST_ATTEMPTS = 3;

    private final ModelAdapter modelAdapter;
    private final AgentEventSink eventSink;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final ContextStatsCalculator contextStatsCalculator;
    private final AutoCompactController autoCompactController;
    private final int maxEmptyResponseRetries;

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink) {
        this(modelAdapter, eventSink, ToolExecutor.unsupported(), 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, ToolExecutor.unsupported(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor) {
        this(modelAdapter, eventSink, toolExecutor, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, ContextManager.noOp(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, defaultContextStatsCalculator(),
                AutoCompactController.disabled(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator,
                AutoCompactController.disabled(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     AutoCompactController autoCompactController) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator,
                autoCompactController, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     AutoCompactController autoCompactController,
                     int maxEmptyResponseRetries) {
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.contextStatsCalculator = Objects.requireNonNull(contextStatsCalculator, "contextStatsCalculator");
        this.autoCompactController = Objects.requireNonNull(autoCompactController, "autoCompactController");
        if (maxEmptyResponseRetries < 0) {
            throw new IllegalArgumentException("maxEmptyResponseRetries must be non-negative");
        }
        this.maxEmptyResponseRetries = maxEmptyResponseRetries;
    }

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        Objects.requireNonNull(request, "request");
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        List<PersistenceAction> actions = new ArrayList<>();
        int emptyResponseCount = 0;
        int recoverableThinkingRetryCount = 0;
        boolean sawToolResultThisTurn = false;
        int toolErrorCount = 0;

        try {
            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.BEFORE_TURN);
            for (int stepIndex = 0; stepIndex < request.maxSteps(); stepIndex++) {
                ContextStats preCompactStats = contextStatsCalculator.calculate(List.copyOf(messages));
                messages = new ArrayList<>(contextManager.microcompact(List.copyOf(messages), preCompactStats));
                ContextStats stats = contextStatsCalculator.calculate(List.copyOf(messages));
                AutoCompactResult autoCompactResult = runAutoCompactPreflight(request.turnId(), messages, actions, stats);
                if (autoCompactResult.status() == CompactStatus.COMPACTED) {
                    messages = new ArrayList<>(autoCompactResult.messages());
                    stats = contextStatsCalculator.calculate(List.copyOf(messages));
                }
                publishEvent(new AgentEvent.ContextStatsEvent(request.turnId(), Instant.now(), stats));
                request.cancellationToken().throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
                AgentStep step;
                try {
                    step = nextWithRetries(List.copyOf(messages), request.cancellationToken());
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
                } catch (CancellationRequestedException exception) {
                    throw exception;
                } catch (ModelRequestException exception) {
                    return modelErrorResult(messages, actions, exception);
                } catch (RuntimeException exception) {
                    return modelErrorResult(messages, actions,
                            exception.getMessage() == null || exception.getMessage().isBlank()
                                    ? "Model adapter failed"
                                    : exception.getMessage(),
                            exception.getClass().getName());
                }

                if (step == null) {
                    return modelErrorResult(messages, actions,
                            "Model adapter returned null AgentStep",
                            NullPointerException.class.getName());
                }

                if (step instanceof ToolCallsStep toolCallsStep) {
                    appendToolCallsStepProjection(request.turnId(), messages, actions, toolCallsStep);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    List<ToolResultMessage> toolResultMessages = new ArrayList<>();

                    for (int callIndex = 0; callIndex < toolCallsStep.calls().size(); callIndex++) {
                        ToolCall call = toolCallsStep.calls().get(callIndex);
                        appendToolCallMessage(request.turnId(), messages, actions, call,
                                callIndex == toolCallsStep.calls().size() - 1
                                        ? toolCallsStep.usage()
                                        : Optional.empty());
                        publishEvent(new AgentEvent.ToolStartedEvent(request.turnId(), Instant.now(),
                                call.id(), call.toolName(), call.input()));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                    }

                    for (ToolCall call : toolCallsStep.calls()) {
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

                        ToolResult result;
                        try {
                            result = toolExecutor.execute(call, createToolContext(request, call.id()));
                            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                        } catch (CancellationRequestedException exception) {
                            throw exception;
                        } catch (RuntimeException exception) {
                            result = ToolResult.error(exception.getMessage() == null || exception.getMessage().isBlank()
                                    ? "Tool execution failed"
                                    : exception.getMessage());
                        }

                        if (result == null) {
                            result = ToolResult.error("Tool executor returned null ToolResult");
                        }
                        sawToolResultThisTurn = true;
                        if (result.error()) {
                            toolErrorCount++;
                        }
                        ToolResultMessage toolResultMessage = appendToolResultMessage(
                                request.turnId(), messages, actions, call, result);
                        toolResultMessages.add(toolResultMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        if (result.awaitUser()) {
                            applyToolResultBudget(request.turnId(), messages, actions, toolResultMessages);
                            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                            publishEvent(new AgentEvent.AwaitUserEvent(
                                    request.turnId(),
                                    Instant.now(),
                                    call.id(),
                                    awaitUserQuestion(call.id(), toolResultMessages)
                            ));
                            return AgentTurnResult.awaitUser(List.copyOf(messages), new TurnPersistencePlan(actions));
                        }
                    }
                    applyToolResultBudget(request.turnId(), messages, actions, toolResultMessages);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    continue;
                }

                if (!(step instanceof AssistantStep assistantStep)) {
                    return modelErrorResult(messages, actions,
                            "AgentLoop only supports AssistantStep and ToolCallsStep",
                            step.getClass().getName());
                }

                if (isRecoverableThinkingStop(assistantStep) && recoverableThinkingRetryCount < 3) {
                    recoverableThinkingRetryCount++;
                    String stopReason = assistantStep.diagnostics().orElseThrow().stopReason().orElse("");
                    AssistantProgressMessage progressMessage = new AssistantProgressMessage(
                            "max_tokens".equals(stopReason)
                                    ? "Model hit max_tokens during thinking; requesting the next actionable step."
                                    : "Model returned pause_turn during thinking; requesting the next actionable step."
                    );
                    appendMessage(request.turnId(), messages, actions, progressMessage);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    appendMessage(request.turnId(), messages, actions, new UserMessage(
                            "max_tokens".equals(stopReason)
                                    ? MAX_TOKENS_THINKING_CONTINUATION_PROMPT
                                    : PAUSE_TURN_THINKING_CONTINUATION_PROMPT
                    ));
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    continue;
                }

                if (assistantStep.content().isBlank()) {
                    emptyResponseCount++;
                    if (emptyResponseCount <= maxEmptyResponseRetries) {
                        appendMessage(request.turnId(), messages, actions,
                                new UserMessage(emptyResponseContinuationPrompt(sawToolResultThisTurn, toolErrorCount)));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        continue;
                    }
                    appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                    String fallbackReason = emptyFallbackReason(sawToolResultThisTurn, toolErrorCount);
                    AssistantMessage fallbackMessage = new AssistantMessage(
                            emptyFallbackMessage(sawToolResultThisTurn, toolErrorCount, assistantStep));
                    appendMessage(request.turnId(), messages, actions, fallbackMessage);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    return AgentTurnResult.emptyFallback(
                            List.copyOf(messages),
                            new TurnPersistencePlan(actions),
                            Optional.of(new EmptyFallbackDetails(
                                    Optional.of(fallbackReason),
                                    Optional.of(emptyFallbackDiagnostics(assistantStep, sawToolResultThisTurn,
                                            toolErrorCount)),
                                    sawToolResultThisTurn,
                                    toolErrorCount
                            ))
                    );
                }

                emptyResponseCount = 0;

                switch (assistantStep.kind()) {
                    case FINAL, UNSPECIFIED -> {
                        appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                        AssistantMessage finalMessage = new AssistantMessage(
                                assistantStep.content(),
                                assistantStep.usage(),
                                UsageStaleness.fresh()
                        );
                        appendMessage(request.turnId(), messages, actions, finalMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        return AgentTurnResult.finalResult(List.copyOf(messages), new TurnPersistencePlan(actions));
                    }
                    case PROGRESS -> {
                        appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                        AssistantProgressMessage progressMessage = new AssistantProgressMessage(
                                assistantStep.content(),
                                assistantStep.usage(),
                                UsageStaleness.fresh()
                        );
                        appendMessage(request.turnId(), messages, actions, progressMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        appendMessage(request.turnId(), messages, actions, new UserMessage(PROGRESS_CONTINUATION_PROMPT));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    }
                }
            }

            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
            return AgentTurnResult.maxSteps(List.copyOf(messages), new TurnPersistencePlan(actions));
        } catch (CancellationRequestedException exception) {
            return cancelledResult(request.turnId(), messages, actions, exception.cancellation());
        }
    }

    private AgentStep nextWithRetries(List<ChatMessage> messages, CancellationToken cancellationToken) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MODEL_REQUEST_ATTEMPTS; attempt++) {
            try {
                return modelAdapter.next(messages);
            } catch (CancellationRequestedException exception) {
                throw exception;
            } catch (ModelRequestException exception) {
                lastException = exception;
            } catch (RuntimeException exception) {
                lastException = exception;
            }
            cancellationToken.throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
        }
        throw Objects.requireNonNull(lastException, "lastException");
    }

    private static ContextStatsCalculator defaultContextStatsCalculator() {
        return new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(128_000, 8_000));
    }

    private AutoCompactResult runAutoCompactPreflight(String turnId, List<ChatMessage> messages,
                                                      List<PersistenceAction> actions, ContextStats stats) {
        if (autoCompactController.willAttempt(List.copyOf(messages), stats)) {
            publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                    AutoCompactEventType.STARTED, Optional.empty(), Optional.empty()));
        }
        AutoCompactResult result = autoCompactController.preflight(List.copyOf(messages), stats, modelAdapter);
        switch (result.status()) {
            case COMPACTED -> {
                publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                        AutoCompactEventType.COMPLETED, Optional.of(result.compressionResult()), Optional.empty()));
                appendAutoCompactPersistenceActions(actions, result);
            }
            case FAILED -> {
                publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                        AutoCompactEventType.FAILED, Optional.empty(),
                        Optional.of(result.reason().orElse("auto compact failed"))));
            }
            case SKIPPED -> publishAutoCompactSkippedIfRelevant(turnId, result);
        }
        return result;
    }

    private void publishAutoCompactSkippedIfRelevant(String turnId, AutoCompactResult result) {
        String reason = result.reason().orElse("");
        if (reason.contains("below auto compact threshold")
                || reason.contains("below auto compact minimum")
                || reason.contains("auto compact disabled")) {
            return;
        }
        publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                AutoCompactEventType.SKIPPED, Optional.empty(), Optional.of(reason)));
    }

    private void appendAutoCompactPersistenceActions(List<PersistenceAction> actions, AutoCompactResult result) {
        minicode.context.compact.CompressionBoundaryResult boundary = result.boundary().orElseThrow();
        actions.add(new PersistenceAction.AppendCompactBoundaryAction(boundary.summaryMessage(), boundary.metadata()));
        List<ChatMessage> retainedMessages = retainedMessagesAfterCompactBoundary(result.messages(), boundary.summaryMessage());
        if (!retainedMessages.isEmpty()) {
            actions.add(new PersistenceAction.AppendMessagesAction(retainedMessages));
        }
    }

    private List<ChatMessage> retainedMessagesAfterCompactBoundary(List<ChatMessage> compactedMessages,
                                                                   ChatMessage summaryMessage) {
        boolean skippedSummary = false;
        List<ChatMessage> retained = new ArrayList<>();
        for (ChatMessage message : compactedMessages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (!skippedSummary && message.equals(summaryMessage)) {
                skippedSummary = true;
                continue;
            }
            retained.add(message);
        }
        return List.copyOf(retained);
    }

    private void appendMessage(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                               ChatMessage message) {
        messages.add(message);
        actions.add(new PersistenceAction.AppendMessagesAction(List.of(message)));
        publishEvent(new AgentEvent.AssistantMessageEvent(turnId, Instant.now(), message));
    }

    private void appendAssistantThinkingBlocks(String turnId, List<ChatMessage> messages,
                                               List<PersistenceAction> actions, AssistantStep step) {
        if (!step.thinkingBlocks().isEmpty()) {
            appendMessage(turnId, messages, actions, new AssistantThinkingMessage(step.thinkingBlocks()));
        }
    }

    private void appendToolCallMessage(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                       ToolCall call, Optional<minicode.model.ProviderUsage> usage) {
        AssistantToolCallMessage message = new AssistantToolCallMessage(
                call.id(),
                call.toolName(),
                call.input(),
                usage,
                UsageStaleness.fresh()
        );
        appendMessage(turnId, messages, actions, message);
    }

    private ToolResultMessage appendToolResultMessage(String turnId, List<ChatMessage> messages,
                                                      List<PersistenceAction> actions, ToolCall call,
                                                      ToolResult result) {
        ToolResultMessage originalMessage = new ToolResultMessage(call.id(), call.toolName(), result.content(), result.error());
        ToolResultReplacementResult replacementResult = contextManager.replaceLargeToolResult(originalMessage);
        ToolResultMessage message = replacementResult.message();
        appendMessage(turnId, messages, actions, message);
        publishEvent(new AgentEvent.ToolFinishedEvent(
                turnId,
                Instant.now(),
                call.id(),
                call.toolName(),
                result.error(),
                result.awaitUser(),
                replacementResult.replacement()
        ));
        return message;
    }

    private void applyToolResultBudget(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                       List<ToolResultMessage> toolResultMessages) {
        ToolResultBudgetResult budgetResult = contextManager.applyToolResultBudget(List.copyOf(toolResultMessages));
        applyBudgetedToolResults(messages, actions, toolResultMessages, budgetResult.results());
        toolResultMessages.clear();
        toolResultMessages.addAll(budgetResult.results());
        if (!budgetResult.replacements().isEmpty()) {
            publishEvent(new ToolResultsBudgetedEvent(turnId, Instant.now(), budgetResult.replacements()));
        }
    }

    private void applyBudgetedToolResults(List<ChatMessage> messages, List<PersistenceAction> actions,
                                          List<ToolResultMessage> originalResults,
                                          List<ToolResultMessage> budgetedResults) {
        if (originalResults.size() != budgetedResults.size()) {
            throw new IllegalStateException("budgeted tool result count must match original result count");
        }
        for (int index = 0; index < originalResults.size(); index++) {
            ToolResultMessage original = originalResults.get(index);
            ToolResultMessage budgeted = budgetedResults.get(index);
            if (original.equals(budgeted)) {
                continue;
            }
            replaceToolResultMessage(messages, original, budgeted);
            replaceToolResultAction(actions, original, budgeted);
        }
    }

    private void replaceToolResultMessage(List<ChatMessage> messages, ToolResultMessage original,
                                          ToolResultMessage replacement) {
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof ToolResultMessage toolResult && sameToolResultSlot(toolResult, original)) {
                messages.set(index, replacement);
                return;
            }
        }
    }

    private void replaceToolResultAction(List<PersistenceAction> actions, ToolResultMessage original,
                                         ToolResultMessage replacement) {
        for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
            PersistenceAction action = actions.get(actionIndex);
            if (action instanceof PersistenceAction.AppendMessagesAction appendMessagesAction) {
                List<ChatMessage> actionMessages = appendMessagesAction.messages();
                for (int messageIndex = 0; messageIndex < actionMessages.size(); messageIndex++) {
                    ChatMessage message = actionMessages.get(messageIndex);
                    if (message instanceof ToolResultMessage toolResult && sameToolResultSlot(toolResult, original)) {
                        List<ChatMessage> replacementMessages = new ArrayList<>(actionMessages);
                        replacementMessages.set(messageIndex, replacement);
                        actions.set(actionIndex, new PersistenceAction.AppendMessagesAction(replacementMessages));
                        return;
                    }
                }
            }
        }
    }

    private boolean sameToolResultSlot(ToolResultMessage candidate, ToolResultMessage expected) {
        return candidate.toolUseId().equals(expected.toolUseId())
                && candidate.toolName().equals(expected.toolName())
                && candidate.content().equals(expected.content())
                && candidate.error() == expected.error();
    }

    private String awaitUserQuestion(String toolUseId, List<ToolResultMessage> toolResultMessages) {
        return toolResultMessages.stream()
                .filter(message -> message.toolUseId().equals(toolUseId))
                .findFirst()
                .map(ToolResultMessage::content)
                .orElse("");
    }

    private void appendToolCallsStepProjection(String turnId, List<ChatMessage> messages,
                                               List<PersistenceAction> actions, ToolCallsStep step) {
        step.content()
                .filter(content -> !content.isBlank())
                .ifPresent(content -> {
                    appendMessage(turnId, messages, actions, projectToolCallsContent(step, content));
                    if (step.contentKind() == minicode.core.step.ContentKind.PROGRESS) {
                        appendMessage(turnId, messages, actions, new UserMessage(PROGRESS_CONTINUATION_PROMPT));
                    }
                });

        if (!step.thinkingBlocks().isEmpty()) {
            appendMessage(turnId, messages, actions, new AssistantThinkingMessage(step.thinkingBlocks()));
        }
    }

    private ChatMessage projectToolCallsContent(ToolCallsStep step, String content) {
        return switch (step.contentKind()) {
            case PROGRESS -> new AssistantProgressMessage(content);
            case UNSPECIFIED -> new AssistantMessage(content);
        };
    }

    private boolean isRecoverableThinkingStop(AssistantStep step) {
        if (!step.content().isBlank() || step.diagnostics().isEmpty()) {
            return false;
        }
        minicode.model.StepDiagnostics diagnostics = step.diagnostics().orElseThrow();
        String stopReason = diagnostics.stopReason().orElse("");
        if (!"pause_turn".equals(stopReason) && !"max_tokens".equals(stopReason)) {
            return false;
        }
        return !step.thinkingBlocks().isEmpty()
                || diagnostics.blockTypes().contains("thinking")
                || diagnostics.ignoredBlockTypes().contains("thinking");
    }

    private String emptyResponseContinuationPrompt(boolean sawToolResultThisTurn, int toolErrorCount) {
        if (toolErrorCount > 0) {
            return EMPTY_RESPONSE_AFTER_TOOL_ERROR_CONTINUATION_PROMPT;
        }
        if (sawToolResultThisTurn) {
            return EMPTY_RESPONSE_AFTER_TOOL_RESULT_CONTINUATION_PROMPT;
        }
        return EMPTY_RESPONSE_CONTINUATION_PROMPT;
    }

    private String emptyFallbackReason(boolean sawToolResultThisTurn, int toolErrorCount) {
        if (toolErrorCount > 0) {
            return "empty_after_tool_error";
        }
        if (sawToolResultThisTurn) {
            return "empty_after_tool_result";
        }
        return "empty_response_retry_exhausted";
    }

    private String emptyFallbackMessage(boolean sawToolResultThisTurn, int toolErrorCount, AssistantStep step) {
        String diagnostics = formatDiagnostics(step);
        if (toolErrorCount > 0) {
            return "The model returned an empty response after recent tool results. Stopping this turn after "
                    + toolErrorCount + (toolErrorCount == 1 ? " tool error" : " tool errors") + "."
                    + diagnostics;
        }
        if (sawToolResultThisTurn) {
            return "The model returned an empty response after recent tool results. Stopping this turn; retry or ask the model to continue from the tool output."
                    + diagnostics;
        }
        return EMPTY_RESPONSE_MESSAGE + diagnostics;
    }

    private String emptyFallbackDiagnostics(AssistantStep step, boolean sawToolResultThisTurn, int toolErrorCount) {
        List<String> parts = new ArrayList<>();
        parts.add("reason=" + emptyFallbackReason(sawToolResultThisTurn, toolErrorCount));
        parts.add("sawToolResultThisTurn=" + sawToolResultThisTurn);
        parts.add("toolErrorCount=" + toolErrorCount);
        step.diagnostics().flatMap(minicode.model.StepDiagnostics::stopReason)
                .ifPresent(stopReason -> parts.add("stopReason=" + stopReason));
        step.diagnostics().ifPresent(diagnostics -> {
            if (!diagnostics.blockTypes().isEmpty()) {
                parts.add("blocks=" + String.join(",", diagnostics.blockTypes()));
            }
            if (!diagnostics.ignoredBlockTypes().isEmpty()) {
                parts.add("ignored=" + String.join(",", diagnostics.ignoredBlockTypes()));
            }
        });
        return String.join("; ", parts);
    }

    private String formatDiagnostics(AssistantStep step) {
        if (step.diagnostics().isEmpty()) {
            return "";
        }
        minicode.model.StepDiagnostics diagnostics = step.diagnostics().orElseThrow();
        List<String> parts = new ArrayList<>();
        diagnostics.stopReason().ifPresent(stopReason -> parts.add("stopReason=" + stopReason));
        if (!diagnostics.blockTypes().isEmpty()) {
            parts.add("blocks=" + String.join(",", diagnostics.blockTypes()));
        }
        if (!diagnostics.ignoredBlockTypes().isEmpty()) {
            parts.add("ignored=" + String.join(",", diagnostics.ignoredBlockTypes()));
        }
        return parts.isEmpty() ? "" : " Diagnostics: " + String.join("; ", parts) + ".";
    }

    private ToolContext createToolContext(AgentTurnRequest request, String toolUseId) {
        return new ToolContext(request.cwd(), request.sessionId(), Optional.of(request.turnId()), Optional.of(toolUseId),
                request.cancellationToken());
    }

    private void publishEvent(AgentEvent event) {
        try {
            eventSink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Sink failures are observational and must not interrupt core turn progression.
        }
    }

    private AgentTurnResult modelErrorResult(List<ChatMessage> messages, List<PersistenceAction> actions,
                                             String errorMessage, String causeClass) {
        return AgentTurnResult.modelError(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new ModelErrorDetails(new TurnError(
                        errorMessage,
                        TurnErrorSource.MODEL,
                        false,
                        Optional.empty(),
                        Optional.of(causeClass)
                ))
        );
    }

    private AgentTurnResult modelErrorResult(List<ChatMessage> messages, List<PersistenceAction> actions,
                                             ModelRequestException exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Model request failed"
                : exception.getMessage();
        Optional<String> diagnostics = enrichedModelErrorDiagnostics(exception, messages);
        return AgentTurnResult.modelError(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new ModelErrorDetails(new TurnError(
                        message,
                        TurnErrorSource.MODEL,
                        exception.retryable(),
                        diagnostics,
                        Optional.of(ModelRequestException.class.getName())
                ))
        );
    }

    private Optional<String> enrichedModelErrorDiagnostics(ModelRequestException exception, List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        exception.diagnostics().ifPresent(parts::add);
        exception.statusCode().ifPresent(statusCode -> {
            String existingDiagnostics = exception.diagnostics().orElse("");
            String normalized = existingDiagnostics.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (existingDiagnostics.isBlank()
                    || (!normalized.contains("statuscode=") && !normalized.contains("status="))) {
                parts.add("statusCode=" + statusCode);
            }
        });
        if (recentToolHistory(messages)) {
            parts.add("recentToolHistory=true");
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", parts));
    }

    private boolean recentToolHistory(List<ChatMessage> messages) {
        int start = Math.max(0, messages.size() - 12);
        for (int index = start; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof AssistantToolCallMessage || message instanceof ToolResultMessage) {
                return true;
            }
        }
        return false;
    }

    private AgentTurnResult cancelledResult(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                            TurnCancellation cancellation) {
        publishEvent(new AgentEvent.TurnCancelledEvent(turnId, Instant.now(), cancellation));
        return AgentTurnResult.cancelled(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new CancellationDetails(cancellation)
        );
    }
}
