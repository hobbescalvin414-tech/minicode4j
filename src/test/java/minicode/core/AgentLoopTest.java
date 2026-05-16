package minicode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactPolicy;
import minicode.context.compact.CompactService;
import minicode.context.manager.ContextManager;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.context.accounting.TokenAccountingService;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.event.ToolResultsBudgetedEvent;
import minicode.core.loop.AgentLoop;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.*;
import minicode.core.step.*;
import minicode.core.turn.*;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.ModelRequestException;
import minicode.session.plan.PersistenceAction;
import minicode.tools.builtin.AskUserTool;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ToolExecutor;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import minicode.tools.result.ToolResultStorage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-16T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void finalAssistantStepAppendsMessageAndStops() {
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(messages -> new AssistantStep("done", AssistantKind.FINAL), eventSink);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi")), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(2, result.messages().size());
        assertInstanceOf(AssistantMessage.class, result.messages().get(1));
        assertEquals(1, result.persistencePlan().actions().size());
        PersistenceAction.AppendMessagesAction action = assertInstanceOf(
                PersistenceAction.AppendMessagesAction.class,
                result.persistencePlan().actions().get(0)
        );
        assertEquals(1, action.messages().size());
        assertTrue(eventSink.events.stream().anyMatch(AgentEvent.AssistantMessageEvent.class::isInstance));
    }

    @Test
    void progressStepContinuesUntilFinal() {
        RecordingEventSink eventSink = new RecordingEventSink();
        SequenceModelAdapter adapter = new SequenceModelAdapter(
                new AssistantStep("working", AssistantKind.PROGRESS),
                new AssistantStep("done", AssistantKind.FINAL)
        );
        AgentLoop loop = new AgentLoop(adapter, eventSink);

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(3, result.messages().size());
        assertInstanceOf(AssistantProgressMessage.class, result.messages().get(0));
        UserMessage continuation = assertInstanceOf(UserMessage.class, result.messages().get(1));
        assertTrue(continuation.content().contains("Continue immediately"));
        assertInstanceOf(AssistantMessage.class, result.messages().get(2));
        assertEquals(3, result.persistencePlan().actions().size());
        assertEquals(5, eventSink.events.size());
        assertEquals(2, eventSink.events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .count());
    }

    @Test
    void multipleProgressStepsReturnFullProjection() {
        AgentLoop loop = new AgentLoop(new SequenceModelAdapter(
                new AssistantStep("p1", AssistantKind.PROGRESS),
                new AssistantStep("p2", AssistantKind.PROGRESS),
                new AssistantStep("done", AssistantKind.FINAL)
        ), AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(List.of(new SystemMessage("sys")), 5));

        assertEquals(6, result.messages().size());
        assertEquals("sys", ((SystemMessage) result.messages().get(0)).content());
        assertInstanceOf(AssistantProgressMessage.class, result.messages().get(1));
        assertInstanceOf(UserMessage.class, result.messages().get(2));
        assertInstanceOf(AssistantProgressMessage.class, result.messages().get(3));
        assertInstanceOf(UserMessage.class, result.messages().get(4));
        assertInstanceOf(AssistantMessage.class, result.messages().get(5));
    }

    @Test
    void emptyResponsesFallBackAfterRetryBudget() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new AssistantStep("", AssistantKind.UNSPECIFIED),
                        new AssistantStep("", AssistantKind.UNSPECIFIED)
                ),
                AgentEventSink.noOp(),
                1
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 5));

        assertEquals(AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, result.stopReason());
        assertEquals(2, result.messages().size());
        assertInstanceOf(UserMessage.class, result.messages().get(0));
        assertInstanceOf(AssistantMessage.class, result.messages().get(1));
        assertTrue(((AssistantMessage) result.messages().get(1)).content().contains("empty"));
        assertTrue(result.stopDetails().isPresent());
        assertInstanceOf(EmptyFallbackDetails.class, result.stopDetails().orElseThrow());
    }

    @Test
    void defaultEmptyResponseRetryBudgetAllowsTwoRetriesBeforeFallback() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new AssistantStep("", AssistantKind.UNSPECIFIED),
                        new AssistantStep("", AssistantKind.UNSPECIFIED),
                        new AssistantStep("recovered", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp()
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 5));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("recovered", assistant.content());
    }

    @Test
    void emptyResponseFallbackDetailsDistinguishRecentToolErrors() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "fake_tool")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("", AssistantKind.UNSPECIFIED),
                        new AssistantStep("", AssistantKind.UNSPECIFIED),
                        new AssistantStep("", AssistantKind.UNSPECIFIED)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.error("tool failed")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 6));

        assertEquals(AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, result.stopReason());
        EmptyFallbackDetails details = assertInstanceOf(EmptyFallbackDetails.class, result.stopDetails().orElseThrow());
        assertEquals(Optional.of("empty_after_tool_error"), details.reason());
        assertTrue(details.diagnostics().orElseThrow().contains("toolErrorCount=1"));
        AssistantMessage fallback = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertTrue(fallback.content().contains("recent tool results"));
        assertTrue(fallback.content().contains("1 tool error"));
    }

    @Test
    void progressOnlyStopsAtMaxSteps() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new AssistantStep("p1", AssistantKind.PROGRESS),
                        new AssistantStep("p2", AssistantKind.PROGRESS),
                        new AssistantStep("p3", AssistantKind.PROGRESS)
                ),
                AgentEventSink.noOp()
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 2));

        assertEquals(AgentTurnStopReason.MAX_STEPS, result.stopReason());
        assertEquals(4, result.messages().size());
        assertEquals(4, result.persistencePlan().actions().size());
    }

    @Test
    void modelExceptionsBecomeModelErrorResults() {
        AgentLoop loop = new AgentLoop(messages -> {
            throw new IllegalStateException("boom");
        }, AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi")), 2));

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(1, result.messages().size());
        assertTrue(result.stopDetails().isPresent());
        ModelErrorDetails details = assertInstanceOf(ModelErrorDetails.class, result.stopDetails().orElseThrow());
        assertEquals(TurnErrorSource.MODEL, details.error().source());
        assertEquals(0, result.persistencePlan().actions().size());
    }

    @Test
    void modelRequestExceptionPreservesProviderNeutralRetryAndStatusDetails() {
        AgentLoop loop = new AgentLoop(messages -> {
            throw new ModelRequestException("rate limited", Optional.of(429), true, Optional.of("status=429"));
        }, AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi")), 2));

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        ModelErrorDetails details = assertInstanceOf(ModelErrorDetails.class, result.stopDetails().orElseThrow());
        assertTrue(details.error().retryable());
        assertEquals(Optional.of("status=429"), details.error().diagnostics());
        assertEquals(Optional.of(ModelRequestException.class.getName()), details.error().causeClass());
    }

    @Test
    void modelRequestIsRetriedThreeAttemptsBeforeReturningModelError() {
        FailingThenSucceedingModelAdapter adapter = new FailingThenSucceedingModelAdapter(2);
        AgentLoop loop = new AgentLoop(adapter, AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi")), 2));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(3, adapter.calls);
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, result.messages().get(1));
        assertEquals("recovered", assistant.content());
    }

    @Test
    void modelRequestErrorAfterThreeAttemptsReturnsModelError() {
        FailingThenSucceedingModelAdapter adapter = new FailingThenSucceedingModelAdapter(3);
        AgentLoop loop = new AgentLoop(adapter, AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi")), 2));

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(3, adapter.calls);
    }

    @Test
    void nullAgentStepBecomesModelErrorResult() {
        AgentLoop loop = new AgentLoop(messages -> null, AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi")), 2));

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        assertTrue(result.stopDetails().isPresent());
        ModelErrorDetails details = assertInstanceOf(ModelErrorDetails.class, result.stopDetails().orElseThrow());
        assertEquals(TurnErrorSource.MODEL, details.error().source());
        assertTrue(details.error().message().contains("null"));
    }

    @Test
    void sinkExceptionsDoNotInterruptTurn() {
        AgentLoop loop = new AgentLoop(
                messages -> new AssistantStep("done", AssistantKind.FINAL),
                event -> {
                    throw new IllegalStateException("sink failed");
                }
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 2));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(1, result.messages().size());
        assertInstanceOf(AssistantMessage.class, result.messages().get(0));
    }

    @Test
    void toolCallsProduceToolResultsThenFinal() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "fake_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("tool output")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(3, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(0));
        assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
        assertInstanceOf(AssistantMessage.class, result.messages().get(2));
        assertFalse(((ToolResultMessage) result.messages().get(1)).error());
    }

    @Test
    void toolStartedEventCarriesProviderNeutralToolInput() {
        RecordingEventSink eventSink = new RecordingEventSink();
        JsonNode input = JsonNodeFactory.instance.objectNode()
                .put("path", "src/main/java/minicode/app/MiniCodeApp.java")
                .put("offset", 10)
                .put("limit", 120);
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(new ToolCall("tool-1", "read_file", input)),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                eventSink,
                (call, context) -> ToolResult.ok("tool output")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AgentEvent.ToolStartedEvent started = eventSink.events.stream()
                .filter(AgentEvent.ToolStartedEvent.class::isInstance)
                .map(AgentEvent.ToolStartedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("tool-1", started.toolUseId());
        assertEquals("read_file", started.toolName());
        assertEquals("src/main/java/minicode/app/MiniCodeApp.java", started.input().get("path").asText());
        assertEquals(10, started.input().get("offset").asInt());
        assertEquals(120, started.input().get("limit").asInt());
    }

    @Test
    void smallToolResultIsNotReplacedByDefaultNoopContextManager() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "fake_tool")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("small")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        ToolResultMessage toolResult = assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
        assertEquals("small", toolResult.content());
    }

    @Test
    void largeToolResultReplacementEntersAgentLoopMessages(@TempDir Path tempDir) {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "read_file")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("abcdefghijklmnopqrstuvwxyz"),
                new ContextManager(new ToolResultStorage(tempDir), 10, 8)
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        ToolResultMessage toolResult = assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
        assertTrue(toolResult.content().contains("<persisted-output"));
        PersistenceAction.AppendMessagesAction action = assertInstanceOf(
                PersistenceAction.AppendMessagesAction.class,
                result.persistencePlan().actions().get(1)
        );
        assertEquals(toolResult, action.messages().getFirst());
    }

    @Test
    void toolFinishedEventCarriesReplacementMetadata(@TempDir Path tempDir) {
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "read_file")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                eventSink,
                (call, context) -> ToolResult.ok("abcdefghijklmnopqrstuvwxyz"),
                new ContextManager(new ToolResultStorage(tempDir), 10, 8)
        );

        loop.runTurn(request(List.of(), 3));

        AgentEvent.ToolFinishedEvent finished = eventSink.events.stream()
                .filter(AgentEvent.ToolFinishedEvent.class::isInstance)
                .map(AgentEvent.ToolFinishedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(finished.replacement().isPresent());
        assertEquals("tool-1", finished.replacement().orElseThrow().toolUseId());
    }

    @Test
    void applyToolResultBudgetIsCalledAfterToolCallsStep(@TempDir Path tempDir) {
        RecordingContextManager contextManager = new RecordingContextManager(tempDir);
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "read_file")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("small"),
                contextManager
        );

        loop.runTurn(request(List.of(), 3));

        assertEquals(1, contextManager.budgetCalls);
        assertEquals(1, contextManager.lastBudgetResults.size());
    }

    @Test
    void batchBudgetedToolResultReplacesFinalMessagesActionsAndPublishesEvent(@TempDir Path tempDir) {
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "grep_files"), call("tool-2", "grep_files")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                eventSink,
                (call, context) -> ToolResult.ok(call.id().equals("tool-1") ? "a".repeat(200) : "b".repeat(200)),
                new ContextManager(new ToolResultStorage(tempDir), 1000, 200, 10)
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        ToolResultsBudgetedEvent budgetedEvent = eventSink.events.stream()
                .filter(ToolResultsBudgetedEvent.class::isInstance)
                .map(ToolResultsBudgetedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertFalse(budgetedEvent.replacements().isEmpty());
        String replacedToolUseId = budgetedEvent.replacements().getFirst().toolUseId();
        String replacementContent = budgetedEvent.replacements().getFirst().replacementContent();

        ToolResultMessage message = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(toolResult -> toolResult.toolUseId().equals(replacedToolUseId))
                .findFirst()
                .orElseThrow();
        assertEquals(replacementContent, message.content());

        ToolResultMessage persistedMessage = result.persistencePlan().actions().stream()
                .filter(PersistenceAction.AppendMessagesAction.class::isInstance)
                .map(PersistenceAction.AppendMessagesAction.class::cast)
                .flatMap(action -> action.messages().stream())
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(toolResult -> toolResult.toolUseId().equals(replacedToolUseId))
                .findFirst()
                .orElseThrow();
        assertEquals(replacementContent, persistedMessage.content());
    }

    @Test
    void awaitUserPathAppliesBatchBudgetBeforeReturning(@TempDir Path tempDir) {
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "ask_user")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                eventSink,
                (call, context) -> new ToolResult("x".repeat(200), false, true, Optional.empty()),
                new ContextManager(new ToolResultStorage(tempDir), 1000, 50, 10)
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.AWAIT_USER, result.stopReason());
        ToolResultsBudgetedEvent budgetedEvent = eventSink.events.stream()
                .filter(ToolResultsBudgetedEvent.class::isInstance)
                .map(ToolResultsBudgetedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        String replacementContent = budgetedEvent.replacements().getFirst().replacementContent();
        ToolResultMessage message = assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
        assertEquals(replacementContent, message.content());
        AgentEvent.AwaitUserEvent awaitUserEvent = eventSink.events.stream()
                .filter(AgentEvent.AwaitUserEvent.class::isInstance)
                .map(AgentEvent.AwaitUserEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(replacementContent, awaitUserEvent.question());
        assertNotEquals("x".repeat(200), awaitUserEvent.question());
        PersistenceAction.AppendMessagesAction action = assertInstanceOf(
                PersistenceAction.AppendMessagesAction.class,
                result.persistencePlan().actions().get(1)
        );
        assertEquals(message, action.messages().getFirst());
    }

    @Test
    void microcompactDoesNotAddCompactBoundaryPersistenceAction(@TempDir Path tempDir) {
        AgentLoop loop = new AgentLoop(
                messages -> new AssistantStep("done", AssistantKind.FINAL),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("unused"),
                new ContextManager(new ToolResultStorage(tempDir), 1000, 10),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(120, 20))
        );
        List<ChatMessage> messages = List.of(
                new ToolResultMessage("tool-1", "read_file", "x".repeat(100), false),
                new ToolResultMessage("tool-2", "run_command", "y".repeat(100), false),
                new ToolResultMessage("tool-3", "grep_files", "z".repeat(100), false),
                new ToolResultMessage("tool-4", "list_files", "recent-1", false),
                new ToolResultMessage("tool-5", "read_file", "recent-2", false)
        );

        AgentTurnResult result = loop.runTurn(request(messages, 1));

        assertTrue(result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .anyMatch(message -> message.content().contains("Output cleared")));
        assertTrue(result.persistencePlan().actions().stream()
                .noneMatch(PersistenceAction.AppendCompactBoundaryAction.class::isInstance));
    }

    @Test
    void awaitUserPathPublishesAwaitUserEvent() {
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "ask_user")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                eventSink,
                (call, context) -> new ToolResult("Need your decision", false, true, Optional.empty())
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.AWAIT_USER, result.stopReason());
        AgentEvent.AwaitUserEvent awaitUserEvent = eventSink.events.stream()
                .filter(AgentEvent.AwaitUserEvent.class::isInstance)
                .map(AgentEvent.AwaitUserEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("tool-1", awaitUserEvent.toolUseId());
        assertEquals("Need your decision", awaitUserEvent.question());
    }

    @Test
    void toolCallsStepContentIsProjectedIntoMessagesWithUsage() {
        ProviderUsage usage = new ProviderUsage(10, 5, 15);
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "fake_tool")),
                                Optional.of("working through tools"),
                                ContentKind.PROGRESS,
                                List.of(),
                                Optional.empty(),
                                Optional.of(usage)
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("tool output")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        AssistantProgressMessage progress = assertInstanceOf(AssistantProgressMessage.class, result.messages().get(0));
        assertEquals("working through tools", progress.content());
        assertTrue(progress.providerUsage().isEmpty());
        assertInstanceOf(UserMessage.class, result.messages().get(1));
        AssistantToolCallMessage toolCall = assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(2));
        assertEquals(usage, toolCall.providerUsage().orElseThrow());
        assertInstanceOf(ToolResultMessage.class, result.messages().get(3));
    }

    @Test
    void toolCallsStepThinkingBlocksAreProjectedIntoMessages() {
        ProviderThinkingBlock block = new ProviderThinkingBlock("thinking", JsonNodeFactory.instance.objectNode().put("text", "hmm"));
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "fake_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(block),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("tool output")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        AssistantThinkingMessage thinking = assertInstanceOf(AssistantThinkingMessage.class, result.messages().get(0));
        assertEquals(1, thinking.blocks().size());
        assertEquals(block, thinking.blocks().get(0));
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(1));
    }

    @Test
    void assistantStepThinkingBlocksArePersistedBeforeFinalAssistantMessage() {
        ProviderThinkingBlock block = new ProviderThinkingBlock("thinking",
                JsonNodeFactory.instance.objectNode().put("text", "ordinary assistant thinking"));
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new AssistantStep("done", AssistantKind.FINAL, List.of(block), Optional.empty(), Optional.empty())
                ),
                AgentEventSink.noOp()
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        AssistantThinkingMessage thinking = assertInstanceOf(AssistantThinkingMessage.class, result.messages().get(0));
        assertEquals(List.of(block), thinking.blocks());
        assertInstanceOf(AssistantMessage.class, result.messages().get(1));
        PersistenceAction.AppendMessagesAction firstAction = assertInstanceOf(
                PersistenceAction.AppendMessagesAction.class,
                result.persistencePlan().actions().get(0)
        );
        assertEquals(thinking, firstAction.messages().getFirst());
    }

    @Test
    void publishesContextStatsBeforeModelStep() {
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(new AssistantStep("done", AssistantKind.FINAL)),
                eventSink,
                ToolExecutor.unsupported(),
                ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(100, 20))
        );

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hello")), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AgentEvent.ContextStatsEvent statsEvent = eventSink.events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("turn-1", statsEvent.turnId());
        assertEquals(80, statsEvent.stats().effectiveInput());
    }

    @Test
    void publishesContextStatsForMicrocompactedMessagesBeforeModelStep(@TempDir Path tempDir) {
        RecordingEventSink eventSink = new RecordingEventSink();
        ProviderUsage usage = new ProviderUsage(40, 20, 60);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new AssistantToolCallMessage("tool-0", "read_file",
                JsonNodeFactory.instance.objectNode(), Optional.of(usage), minicode.model.UsageStaleness.fresh()));
        for (int index = 1; index <= 5; index++) {
            messages.add(new ToolResultMessage("tool-" + index, "read_file", "x".repeat(40), false));
        }
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(new AssistantStep("done", AssistantKind.FINAL)),
                eventSink,
                ToolExecutor.unsupported(),
                new ContextManager(new ToolResultStorage(tempDir), 1000, 10),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(120, 20))
        );

        AgentTurnResult result = loop.runTurn(request(messages, 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AgentEvent.ContextStatsEvent statsEvent = eventSink.events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0, statsEvent.stats().accounting().providerUsageTokens());
        assertTrue(statsEvent.stats().accounting().stale());
        AssistantToolCallMessage compactedToolCall = assertInstanceOf(AssistantToolCallMessage.class,
                result.messages().getFirst());
        assertTrue(compactedToolCall.usageStaleness().stale());
    }

    @Test
    void autoCompactRunsBeforeProviderRequestAndUsesCompactedMessages() {
        RecordingEventSink eventSink = new RecordingEventSink();
        AutoCompactAwareModelAdapter model = new AutoCompactAwareModelAdapter();
        AgentLoop loop = new AgentLoop(
                model,
                eventSink,
                ToolExecutor.unsupported(),
                ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_000, 100)),
                new AutoCompactController(new CompactService(FIXED_CLOCK),
                        new AutoCompactPolicy(0.85d, 3, 0, 2))
        );

        AgentTurnResult result = loop.runTurn(request(longConversation(18), 2));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(1, model.summaryCalls);
        assertEquals(1, model.mainCalls.size());
        List<ChatMessage> providerMessages = model.mainCalls.getFirst();
        assertTrue(providerMessages.stream().anyMatch(ContextSummaryMessage.class::isInstance));
        assertFalse(providerMessages.stream().anyMatch(message -> message instanceof UserMessage user
                && user.content().startsWith("user-0 ")));
        assertTrue(eventSink.events.stream().anyMatch(event -> event instanceof AgentEvent.AutoCompactEvent auto
                && auto.type() == minicode.context.compact.AutoCompactEventType.STARTED));
        assertTrue(eventSink.events.stream().anyMatch(event -> event instanceof AgentEvent.AutoCompactEvent auto
                && auto.type() == minicode.context.compact.AutoCompactEventType.COMPLETED));
        assertTrue(result.persistencePlan().actions().stream()
                .anyMatch(PersistenceAction.AppendCompactBoundaryAction.class::isInstance));
        assertTrue(result.persistencePlan().actions().stream()
                .filter(PersistenceAction.AppendMessagesAction.class::isInstance)
                .map(PersistenceAction.AppendMessagesAction.class::cast)
                .flatMap(action -> action.messages().stream())
                .anyMatch(message -> message instanceof UserMessage user && user.content().startsWith("user-")));
    }

    @Test
    void autoCompactSkipsUnsafeDanglingToolRoundBeforeProviderRequest() {
        RecordingEventSink eventSink = new RecordingEventSink();
        AutoCompactAwareModelAdapter model = new AutoCompactAwareModelAdapter();
        List<ChatMessage> messages = new ArrayList<>(longConversation(18));
        messages.add(new AssistantToolCallMessage("tool-open", "read_file", JsonNodeFactory.instance.objectNode()));
        AgentLoop loop = new AgentLoop(
                model,
                eventSink,
                ToolExecutor.unsupported(),
                ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_000, 100)),
                new AutoCompactController(new CompactService(FIXED_CLOCK),
                        new AutoCompactPolicy(0.85d, 3, 0, 2))
        );

        AgentTurnResult result = loop.runTurn(request(List.copyOf(messages), 1));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(0, model.summaryCalls);
        assertEquals(1, model.mainCalls.size());
        assertTrue(model.mainCalls.getFirst().stream().anyMatch(message -> message instanceof AssistantToolCallMessage toolCall
                && toolCall.toolUseId().equals("tool-open")));
        assertTrue(eventSink.events.stream().anyMatch(event -> event instanceof AgentEvent.AutoCompactEvent auto
                && auto.type() == minicode.context.compact.AutoCompactEventType.SKIPPED
                && auto.reason().orElse("").contains("unsafe")));
        assertTrue(result.persistencePlan().actions().stream()
                .noneMatch(PersistenceAction.AppendCompactBoundaryAction.class::isInstance));
    }

    @Test
    void autoCompactFailureUsesCooldownInsteadOfRetryingEveryProviderPreflight() {
        RecordingEventSink eventSink = new RecordingEventSink();
        ModelAdapter model = messages -> {
            boolean summaryRequest = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .map(UserMessage::content)
                    .anyMatch(content -> content.contains("Summarize the following MiniCode"));
            if (summaryRequest) {
                throw new ModelRequestException("summary unavailable", Optional.empty(), true, Optional.empty());
            }
            return new AssistantStep("progress", AssistantKind.PROGRESS);
        };
        AgentLoop loop = new AgentLoop(
                model,
                eventSink,
                ToolExecutor.unsupported(),
                ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_000, 100)),
                new AutoCompactController(new CompactService(FIXED_CLOCK),
                        new AutoCompactPolicy(0.85d, 3, 0, 2))
        );

        AgentTurnResult result = loop.runTurn(request(longConversation(18), 3));

        assertEquals(AgentTurnStopReason.MAX_STEPS, result.stopReason());
        long failedEvents = eventSink.events.stream()
                .filter(event -> event instanceof AgentEvent.AutoCompactEvent auto
                        && auto.type() == minicode.context.compact.AutoCompactEventType.FAILED)
                .count();
        long cooldownSkips = eventSink.events.stream()
                .filter(event -> event instanceof AgentEvent.AutoCompactEvent auto
                        && auto.type() == minicode.context.compact.AutoCompactEventType.SKIPPED
                        && auto.reason().orElse("").contains("cooldown"))
                .count();
        assertEquals(1, failedEvents);
        assertEquals(2, cooldownSkips);
    }

    @Test
    void autoCompactRetriesAfterFuseCooldownInsteadOfPermanentlyDisabling() {
        RecoveringAutoCompactModelAdapter model = new RecoveringAutoCompactModelAdapter(2);
        AgentLoop loop = new AgentLoop(
                model,
                AgentEventSink.noOp(),
                ToolExecutor.unsupported(),
                ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_000, 100)),
                new AutoCompactController(new CompactService(FIXED_CLOCK),
                        new AutoCompactPolicy(0.85d, 2, 0, 1))
        );

        AgentTurnResult result = loop.runTurn(request(longConversation(18), 6));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(3, model.summaryCalls);
        assertTrue(result.messages().stream().anyMatch(ContextSummaryMessage.class::isInstance));
        assertTrue(result.persistencePlan().actions().stream()
                .anyMatch(PersistenceAction.AppendCompactBoundaryAction.class::isInstance));
    }

    @Test
    void autoCompactAfterCompleteToolRoundRunsBeforeNextProviderRequest() {
        RecordingEventSink eventSink = new RecordingEventSink();
        AutoCompactAfterToolModelAdapter model = new AutoCompactAfterToolModelAdapter();
        AgentLoop loop = new AgentLoop(
                model,
                eventSink,
                (call, context) -> ToolResult.ok("tool output " + "z".repeat(200)),
                ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_400, 100)),
                new AutoCompactController(new CompactService(FIXED_CLOCK),
                        new AutoCompactPolicy(0.53d, 3, 0, 2))
        );

        AgentTurnResult result = loop.runTurn(request(longConversation(12), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(1, model.summaryCalls);
        List<ChatMessage> secondProviderRequest = model.mainCalls.get(1);
        int toolCallIndex = indexOf(secondProviderRequest, AssistantToolCallMessage.class);
        int toolResultIndex = indexOf(secondProviderRequest, ToolResultMessage.class);
        assertTrue(toolCallIndex >= 0);
        assertEquals(toolCallIndex + 1, toolResultIndex);
        assertTrue(secondProviderRequest.stream().anyMatch(ContextSummaryMessage.class::isInstance));
    }

    @Test
    void multipleToolCallsAppendEachCallAndResult() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "first"), call("tool-2", "second")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("result for " + call.toolName())
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(5, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(0));
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(1));
        assertInstanceOf(ToolResultMessage.class, result.messages().get(2));
        assertInstanceOf(ToolResultMessage.class, result.messages().get(3));
        assertInstanceOf(AssistantMessage.class, result.messages().get(4));
    }

    @Test
    void unknownToolOrToolFailureBecomesErrorToolResultMessage() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "missing_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> {
                    throw new IllegalArgumentException("Unknown tool: " + call.toolName());
                }
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        ToolResultMessage toolResult = assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
        assertTrue(toolResult.error());
        assertTrue(toolResult.content().contains("Unknown tool"));
    }

    @Test
    void toolCallsCanReachMaxStepsWithoutFinalAssistant() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "fake_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new ToolCallsStep(
                                List.of(call("tool-2", "fake_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        )
                ),
                AgentEventSink.noOp(),
                (call, context) -> ToolResult.ok("tool output")
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 1));

        assertEquals(AgentTurnStopReason.MAX_STEPS, result.stopReason());
        assertEquals(2, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(0));
        assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
    }

    @Test
    void awaitUserToolResultStopsTurnWithoutCallingModelAgain() {
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "fake_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> new ToolResult("need user input", false, true, Optional.empty())
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.AWAIT_USER, result.stopReason());
        assertEquals(2, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(0));
        assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
    }

    @Test
    void askUserToolViaRegistryStopsTurnAtAwaitUser() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AskUserTool());
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(callWithQuestion("tool-1", "ask_user", "Need your decision")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                registry
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.AWAIT_USER, result.stopReason());
        ToolResultMessage toolResult = assertInstanceOf(ToolResultMessage.class, result.messages().get(1));
        assertFalse(toolResult.error());
        assertTrue(toolResult.content().contains("Need your decision"));
    }

    @Test
    void toolContextIsPassedIntoToolExecutor() {
        RecordingToolExecutor toolExecutor = new RecordingToolExecutor();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(
                                List.of(call("tool-1", "fake_tool")),
                                Optional.empty(),
                                ContentKind.UNSPECIFIED,
                                List.of(),
                                Optional.empty(),
                                Optional.empty()
                        ),
                        new AssistantStep("done", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                toolExecutor
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertNotNull(toolExecutor.lastContext);
        assertEquals(Path.of("E:/Minicode-Java/workspace"), toolExecutor.lastContext.cwd());
        assertEquals("session-123", toolExecutor.lastContext.sessionId());
        assertEquals(Optional.of("turn-1"), toolExecutor.lastContext.turnId());
        assertEquals(Optional.of("tool-1"), toolExecutor.lastContext.toolUseId());
    }

    @Test
    void cancellationBeforeTurnReturnsCancelledEventAndDoesNotCallModel() {
        RecordingEventSink eventSink = new RecordingEventSink();
        CountingModelAdapter adapter = new CountingModelAdapter(new AssistantStep("should not run", AssistantKind.FINAL));
        AgentLoop loop = new AgentLoop(adapter, eventSink);

        AgentTurnResult result = loop.runTurn(request(
                List.of(),
                3,
                CancellationToken.cancelled(CancellationSource.USER, "ctrl-c")
        ));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertEquals(0, adapter.calls);
        assertTrue(result.messages().isEmpty());
        assertTrue(result.persistencePlan().actions().isEmpty());
        AgentEvent.TurnCancelledEvent event = assertCancelledEvent(eventSink);
        assertEquals("turn-1", event.turnId());
        assertEquals(CancellationPhase.BEFORE_TURN, event.cancellation().phase());
    }

    @Test
    void cancellationAfterModelReturnBeforeAppendDoesNotPersistAssistantMessage() {
        RecordingEventSink eventSink = new RecordingEventSink();
        CancellationToken token = CancellationToken.create();
        AgentLoop loop = new AgentLoop(messages -> {
            token.requestCancellation(CancellationSource.USER, "stop after model");
            return new AssistantStep("should not persist", AssistantKind.FINAL);
        }, eventSink);

        AgentTurnResult result = loop.runTurn(request(List.of(), 3, token));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertTrue(result.messages().isEmpty());
        assertTrue(result.persistencePlan().actions().isEmpty());
        assertEquals(CancellationPhase.MODEL_REQUEST, cancellationDetails(result).phase());
        assertCancelledEvent(eventSink);
    }

    @Test
    void cancellationBeforeToolExecutionDoesNotCallToolExecutor() {
        RecordingEventSink eventSink = new RecordingEventSink();
        CancellationToken token = CancellationToken.create();
        ToolExecutor executor = (call, context) -> fail("tool executor should not run after cancellation");
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "fake_tool")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty())
                ),
                event -> {
                    eventSink.onEvent(event);
                    if (event instanceof AgentEvent.ToolStartedEvent) {
                        token.requestCancellation(CancellationSource.USER, "cancel before tool");
                    }
                },
                executor
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3, token));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertEquals(1, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().getFirst());
        assertEquals(1, result.persistencePlan().actions().size());
        assertEquals(CancellationPhase.TOOL_EXECUTION, cancellationDetails(result).phase());
        assertCancelledEvent(eventSink);
    }

    @Test
    void cancellationAfterToolExecutionDoesNotAppendToolResultOrCallModelAgain() {
        CancellationToken token = CancellationToken.create();
        SequenceModelAdapter adapter = new SequenceModelAdapter(
                new ToolCallsStep(List.of(call("tool-1", "fake_tool")), Optional.empty(),
                        ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                new AssistantStep("should not be reached", AssistantKind.FINAL)
        );
        AgentLoop loop = new AgentLoop(
                adapter,
                AgentEventSink.noOp(),
                (call, context) -> {
                    context.cancellationToken().requestCancellation(CancellationSource.USER, "cancel after tool");
                    return ToolResult.ok("tool output should not persist");
                }
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3, token));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertEquals(1, adapter.index);
        assertEquals(1, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().getFirst());
        assertEquals(1, result.persistencePlan().actions().size());
        assertEquals(CancellationPhase.TOOL_EXECUTION, cancellationDetails(result).phase());
    }

    @Test
    void cancellationThrownByToolExecutionReturnsCancelledInsteadOfToolError() {
        CancellationToken token = CancellationToken.create();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "run_command")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                AgentEventSink.noOp(),
                (call, context) -> {
                    context.cancellationToken().requestCancellation(CancellationSource.USER, "tool internal cancel");
                    context.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                    return ToolResult.ok("unreachable");
                }
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3, token));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertEquals(1, result.messages().size());
        assertInstanceOf(AssistantToolCallMessage.class, result.messages().getFirst());
        assertEquals(CancellationPhase.TOOL_EXECUTION, cancellationDetails(result).phase());
    }

    @Test
    void cancellationIsNotReportedAsModelError() {
        AgentLoop loop = new AgentLoop(messages -> new AssistantStep("ignored", AssistantKind.FINAL), AgentEventSink.noOp());

        AgentTurnResult result = loop.runTurn(request(
                List.of(),
                3,
                CancellationToken.cancelled(CancellationSource.SYSTEM, "shutdown")
        ));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertNotEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
    }

    @Test
    void completedMessagesAndActionsRemainWhenCancellationHappensAfterProgressAppend() {
        CancellationToken token = CancellationToken.create();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new AssistantStep("working", AssistantKind.PROGRESS),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                event -> {
                    if (event instanceof AgentEvent.AssistantMessageEvent) {
                        token.requestCancellation(CancellationSource.USER, "cancel after progress");
                    }
                }
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3, token));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertEquals(1, result.messages().size());
        AssistantProgressMessage progress = assertInstanceOf(AssistantProgressMessage.class, result.messages().getFirst());
        assertEquals("working", progress.content());
        PersistenceAction.AppendMessagesAction action = assertInstanceOf(
                PersistenceAction.AppendMessagesAction.class,
                result.persistencePlan().actions().getFirst()
        );
        assertEquals(progress, action.messages().getFirst());
        assertEquals(CancellationPhase.AFTER_TURN, cancellationDetails(result).phase());
    }

    @Test
    void cancellationAfterBatchBudgetKeepsBudgetedToolResultInMessagesAndActions(@TempDir Path tempDir) {
        CancellationToken token = CancellationToken.create();
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new SequenceModelAdapter(
                        new ToolCallsStep(List.of(call("tool-1", "grep_files")), Optional.empty(),
                                ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty()),
                        new AssistantStep("should not be reached", AssistantKind.FINAL)
                ),
                event -> {
                    eventSink.onEvent(event);
                    if (event instanceof ToolResultsBudgetedEvent) {
                        token.requestCancellation(CancellationSource.USER, "cancel after budget");
                    }
                },
                (call, context) -> ToolResult.ok("x".repeat(200)),
                new ContextManager(new ToolResultStorage(tempDir), 1000, 50, 10)
        );

        AgentTurnResult result = loop.runTurn(request(List.of(), 3, token));

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        ToolResultsBudgetedEvent budgetedEvent = eventSink.events.stream()
                .filter(ToolResultsBudgetedEvent.class::isInstance)
                .map(ToolResultsBudgetedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        String replacementContent = budgetedEvent.replacements().getFirst().replacementContent();
        ToolResultMessage message = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(replacementContent, message.content());
        assertNotEquals("x".repeat(200), message.content());
        assertEquals(CancellationPhase.AFTER_TURN, cancellationDetails(result).phase());
    }

    private static final class RecordingEventSink implements AgentEventSink {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
        }
    }

    private static final class SequenceModelAdapter implements ModelAdapter {
        private final List<AgentStep> steps;
        private int index;

        private SequenceModelAdapter(AgentStep... steps) {
            this.steps = List.of(steps);
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            if (index >= steps.size()) {
                return steps.get(steps.size() - 1);
            }
            return steps.get(index++);
        }
    }

    private static final class CountingModelAdapter implements ModelAdapter {
        private final AgentStep step;
        private int calls;

        private CountingModelAdapter(AgentStep step) {
            this.step = step;
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls++;
            return step;
        }
    }

    private static final class FailingThenSucceedingModelAdapter implements ModelAdapter {
        private final int failuresBeforeSuccess;
        private int calls;

        private FailingThenSucceedingModelAdapter(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls++;
            if (calls <= failuresBeforeSuccess) {
                throw new ModelRequestException("temporary model failure", Optional.of(503), true,
                        Optional.of("status=503"));
            }
            return new AssistantStep("recovered", AssistantKind.FINAL);
        }
    }

    private static final class AutoCompactAwareModelAdapter implements ModelAdapter {
        private int summaryCalls;
        private final List<List<ChatMessage>> mainCalls = new ArrayList<>();

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            if (isSummaryRequest(messages)) {
                summaryCalls++;
                return new AssistantStep("auto summary", AssistantKind.FINAL);
            }
            mainCalls.add(List.copyOf(messages));
            return new AssistantStep("done", AssistantKind.FINAL);
        }
    }

    private static final class AutoCompactAfterToolModelAdapter implements ModelAdapter {
        private int summaryCalls;
        private final List<List<ChatMessage>> mainCalls = new ArrayList<>();

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            if (isSummaryRequest(messages)) {
                summaryCalls++;
                return new AssistantStep("auto summary after tool", AssistantKind.FINAL);
            }
            mainCalls.add(List.copyOf(messages));
            if (mainCalls.size() == 1) {
                return new ToolCallsStep(List.of(call("tool-1", "read_file")), Optional.empty(),
                        ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
            }
            return new AssistantStep("done", AssistantKind.FINAL);
        }
    }

    private static final class RecoveringAutoCompactModelAdapter implements ModelAdapter {
        private final int failingSummaryCalls;
        private int summaryCalls;

        private RecoveringAutoCompactModelAdapter(int failingSummaryCalls) {
            this.failingSummaryCalls = failingSummaryCalls;
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            if (isSummaryRequest(messages)) {
                summaryCalls++;
                if (summaryCalls <= failingSummaryCalls) {
                    throw new ModelRequestException("temporary summary failure", Optional.empty(), true,
                            Optional.empty());
                }
                return new AssistantStep("recovered summary", AssistantKind.FINAL);
            }
            if (messages.stream().anyMatch(ContextSummaryMessage.class::isInstance)) {
                return new AssistantStep("done", AssistantKind.FINAL);
            }
            return new AssistantStep("still working", AssistantKind.PROGRESS);
        }
    }

    private static final class RecordingToolExecutor implements ToolExecutor {
        private ToolContext lastContext;

        @Override
        public ToolResult execute(ToolCall call, ToolContext toolContext) {
            lastContext = toolContext;
            return ToolResult.ok("tool output");
        }
    }

    private static final class RecordingContextManager extends ContextManager {
        private int budgetCalls;
        private List<ToolResultMessage> lastBudgetResults = List.of();

        private RecordingContextManager(Path root) {
            super(new ToolResultStorage(root), 1000, 10);
        }

        @Override
        public ToolResultBudgetResult applyToolResultBudget(List<ToolResultMessage> results) {
            budgetCalls++;
            lastBudgetResults = List.copyOf(results);
            return super.applyToolResultBudget(results);
        }
    }

    private static ToolCall call(String id, String name) {
        JsonNode input = JsonNodeFactory.instance.objectNode();
        return new ToolCall(id, name, input);
    }

    private static ToolCall callWithQuestion(String id, String name, String question) {
        JsonNode input = JsonNodeFactory.instance.objectNode().put("question", question);
        return new ToolCall(id, name, input);
    }

    private static AgentTurnRequest request(List<ChatMessage> messages, int maxSteps) {
        return new AgentTurnRequest(
                "turn-1",
                Path.of("E:/Minicode-Java/workspace"),
                "session-123",
                messages,
                maxSteps,
                Optional.empty()
        );
    }

    private static AgentTurnRequest request(List<ChatMessage> messages, int maxSteps, CancellationToken token) {
        return new AgentTurnRequest(
                "turn-1",
                Path.of("E:/Minicode-Java/workspace"),
                "session-123",
                messages,
                maxSteps,
                Optional.empty(),
                token
        );
    }

    private static List<ChatMessage> longConversation(int userAssistantPairs) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        for (int index = 0; index < userAssistantPairs; index++) {
            messages.add(new UserMessage("user-" + index + " " + "x".repeat(100)));
            messages.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(100)));
        }
        return List.copyOf(messages);
    }

    private static boolean isSummaryRequest(List<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::content)
                .anyMatch(content -> content.contains("Summarize the following MiniCode"));
    }

    private static int indexOf(List<ChatMessage> messages, Class<? extends ChatMessage> type) {
        for (int index = 0; index < messages.size(); index++) {
            if (type.isInstance(messages.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static TurnCancellation cancellationDetails(AgentTurnResult result) {
        CancellationDetails details = assertInstanceOf(CancellationDetails.class, result.stopDetails().orElseThrow());
        return details.cancellation();
    }

    private static AgentEvent.TurnCancelledEvent assertCancelledEvent(RecordingEventSink eventSink) {
        return eventSink.events.stream()
                .filter(AgentEvent.TurnCancelledEvent.class::isInstance)
                .map(AgentEvent.TurnCancelledEvent.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
