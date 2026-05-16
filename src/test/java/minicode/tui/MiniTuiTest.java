package minicode.tui;

import minicode.app.ApplicationServices;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.ContentKind;
import minicode.core.message.AssistantMessage;
import minicode.core.step.AssistantStep;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.TurnCancellation;
import minicode.core.message.ChatMessage;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.model.MockModelAdapter;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionDecision;
import minicode.tools.api.ToolCall;

import minicode.session.model.SessionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiniTuiTest {
    @TempDir
    Path tempDir;

    @Test
    void mockModelCanCompleteOneUserToAssistantFinalTurn() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(new MockModelAdapter("mock final"), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("user: hello"));
        assertTrue(text.contains("assistant: mock final"));
        assertFalse(text.contains("turn_stop: FINAL"));
    }

    @Test
    void legacyLineTuiDoesNotPersistThinkingStatus() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(messages -> {
            String text = output.toString(StandardCharsets.UTF_8);
            assertFalse(text.contains("status: Thinking..."), text);
            return new AssistantStep("done", AssistantKind.FINAL);
        }, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("status: Thinking..."), text);
        assertTrue(text.contains("assistant: done"), text);
    }

    @Test
    void toolEventsAreProjectedAsMinimalText() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingSink sink = new RecordingSink();
        ModelAdapter model = messages -> new ToolCallsStep(
                List.of(new ToolCall("tool-1", "ask_user",
                        JsonNodeFactory.instance.objectNode().put("question", "Need input"))),
                java.util.Optional.empty(),
                ContentKind.UNSPECIFIED,
                List.of(),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        );
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                model,
                new MiniTuiEventSink(output, sink),
                PermissionPromptHandler.unavailable()
        );
        MiniTui tui = new MiniTui(services, input("run tool\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("tool_call: ask_user question=\"Need input\""), text);
        assertTrue(text.contains("tool_result: ask_user ok"));
        assertFalse(text.contains("tool_call: ask_user tool-1"));
        assertFalse(text.contains("tool_result: ask_user tool-1 ok"));
        assertTrue(sink.events.stream().anyMatch(AgentEvent.ToolStartedEvent.class::isInstance));
        assertTrue(sink.events.stream().anyMatch(AgentEvent.ToolFinishedEvent.class::isInstance));
    }

    @Test
    void tuiPrintsProgressContentFromToolCallsStepProjection() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(messages -> new ToolCallsStep(
                List.of(new ToolCall("tool-1", "ask_user",
                        JsonNodeFactory.instance.objectNode().put("question", "Need input"))),
                java.util.Optional.of("checking file"),
                ContentKind.PROGRESS,
                List.of(),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        ), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("question\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("progress: checking file"), text);
        assertTrue(text.contains("tool_call: ask_user question=\"Need input\""), text);
        assertTrue(text.contains("turn_stop: AWAIT_USER"), text);
    }

    @Test
    void twoRoundMockConversationRetainsHistoryFromSession() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingModelAdapter model = new RecordingModelAdapter();
        ApplicationServices services = services(model, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("first\nsecond\nquit\n"), output);

        tui.runLoop();

        assertEquals(2, model.calls.size());
        assertEquals(List.of(SystemMessage.class, UserMessage.class), model.calls.get(0).stream().map(Object::getClass).toList());
        assertEquals(List.of(SystemMessage.class, UserMessage.class, AssistantMessage.class, UserMessage.class),
                model.calls.get(1).stream().map(Object::getClass).toList());
        assertEquals("first", ((UserMessage) model.calls.get(1).get(1)).content());
        assertEquals("second", ((UserMessage) model.calls.get(1).get(3)).content());
    }

    @Test
    void sessionJsonlContainsUserAndAssistantMessages() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(new MockModelAdapter("mock final"), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\nexit\n"), output);

        tui.runLoop();

        List<SessionEvent> events = services.sessionStore().readAll("session-1", tempDir.resolve("workspace").toString());
        assertTrue(events.stream().anyMatch(event -> event.message().orElse(null) instanceof UserMessage));
        assertTrue(events.stream().anyMatch(event -> event.message().orElse(null) instanceof AssistantMessage));
    }

    @Test
    void exitAndQuitStopLoopWithoutPersistingCommandAsUserMessage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(new MockModelAdapter("mock final"), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("exit\nquit\n"), output);

        tui.runLoop();

        List<SessionEvent> events = services.sessionStore().readAll("session-1", tempDir.resolve("workspace").toString());
        assertTrue(events.isEmpty());
    }

    @Test
    void slashCompactRunsManualCompactWithoutPersistingUserCommand() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Path workspace = tempDir.resolve("workspace");
        assertDoesNotThrow(() -> java.nio.file.Files.createDirectory(workspace));
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                workspace,
                "session-1",
                messages -> new AssistantStep("compact summary", AssistantKind.FINAL),
                new MiniTuiEventSink(output, event -> {
                }),
                PermissionPromptHandler.unavailable()
        );
        List<ChatMessage> initial = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            initial.add(new UserMessage("user-" + index + " " + "x".repeat(80)));
            initial.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(80)));
        }
        services.sessionPersistenceRunner().apply(new minicode.session.plan.TurnPersistencePlan(List.of(
                new minicode.session.plan.PersistenceAction.AppendMessagesAction(initial)
        )));
        MiniTui tui = new MiniTui(services, input("/compact\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("compact: started"), text);
        assertTrue(text.contains("compact: ok"), text);
        assertFalse(text.contains("user: /compact"), text);
        var events = services.sessionStore().readAll("session-1", workspace.toString());
        assertTrue(events.stream().anyMatch(event -> event.type() == minicode.session.model.SessionEventType.COMPACT_BOUNDARY));
        assertTrue(events.stream().noneMatch(event -> event.message().orElse(null) instanceof UserMessage user
                && user.content().equals("/compact")));
    }

    @Test
    void slashCompactPrintsSkippedWhenNothingToCompact() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(new MockModelAdapter("unused"), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("/compact\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("compact: started"), text);
        assertTrue(text.contains("compact: skipped"), text);
        assertFalse(text.contains("turn_stop:"), text);
    }

    @Test
    void tuiPrintsAwaitUserStopReason() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(messages -> new ToolCallsStep(
                List.of(new ToolCall("tool-1", "ask_user",
                        JsonNodeFactory.instance.objectNode().put("question", "Need input"))),
                java.util.Optional.empty(),
                ContentKind.UNSPECIFIED,
                List.of(),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        ), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("question\n"), output);

        tui.runOnce();

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("turn_stop: AWAIT_USER"));
    }

    @Test
    void tuiPrintsNonFinalStopReasons() {
        assertStopReason(messages -> new AssistantStep("progress", AssistantKind.PROGRESS), "MAX_STEPS");
        assertStopReason(messages -> {
            throw new IllegalStateException("model failed");
        }, "MODEL_ERROR");
        assertStopReason(messages -> new AssistantStep("", AssistantKind.UNSPECIFIED), "EMPTY_RESPONSE_FALLBACK");
        assertStopReason(messages -> {
            throw new CancellationRequestedException(new TurnCancellation(
                    CancellationSource.USER,
                    CancellationPhase.MODEL_REQUEST,
                    "cancelled"
            ));
        }, "CANCELLED");
    }

    @Test
    void defaultMaxStepsAllowsMediumLengthProgressTurns() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int[] calls = {0};
        ApplicationServices services = services(messages -> {
            calls[0]++;
            return calls[0] <= 9
                    ? new AssistantStep("progress-" + calls[0], AssistantKind.PROGRESS)
                    : new AssistantStep("done", AssistantKind.FINAL);
        }, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("assistant: done"), text);
        assertFalse(text.contains("turn_stop: FINAL"), text);
    }

    @Test
    void constructorMaxStepsOverridesDefault() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(new SequenceModelAdapter(
                new AssistantStep("progress", AssistantKind.PROGRESS),
                new AssistantStep("done", AssistantKind.FINAL)
        ), output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output, 1);

        tui.runOnce();

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("turn_stop: MAX_STEPS"));
    }

    @Test
    void tuiPrintsProviderNeutralModelErrorDetails() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(messages -> {
            throw new minicode.model.ModelRequestException("provider timed out", java.util.Optional.of(504), false,
                    java.util.Optional.of("status=504"));
        }, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("model_error: provider timed out"));
        assertTrue(text.contains("retryable=false"));
        assertTrue(text.contains("diagnostics=status=504"));
        assertFalse(text.contains("minicode.model.anthropic"));
    }

    @Test
    void tuiPrintsRecoveryHintForProvider400WithToolHistory() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(messages -> {
            throw new minicode.model.ModelRequestException("Param Incorrect", java.util.Optional.of(400), false,
                    java.util.Optional.of("statusCode=400; recentToolHistory=true"));
        }, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("model_error: Param Incorrect"));
        assertTrue(text.contains("provider rejected the request with status 400"));
        assertTrue(text.contains("Recent history contains tool calls/results"));
        assertTrue(text.contains("Start a new session, or resume/fork before the failing turn"));
    }

    @Test
    void tuiPrintsStopReasonSpecificDetails() {
        assertStopDetail(messages -> new AssistantStep("progress", AssistantKind.PROGRESS),
                "max_steps: reached max steps. Type \"continue\" or \"继续\" to keep going from the current context.");
        assertStopDetail(messages -> new AssistantStep("", AssistantKind.UNSPECIFIED),
                "empty_response_fallback: reason=empty_response_retry_exhausted");
        assertStopDetail(messages -> {
            throw new CancellationRequestedException(new TurnCancellation(
                    CancellationSource.USER,
                    CancellationPhase.MODEL_REQUEST,
                    "ctrl-c"
            ));
        }, "cancelled: source=USER phase=MODEL_REQUEST reason=ctrl-c");
    }

    @Test
    void eventSinkPrintsAwaitUserAndCancellationButHidesContextStatsByDefault() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MiniTuiEventSink sink = new MiniTuiEventSink(output, event -> {
        });

        sink.onEvent(new AgentEvent.ContextStatsEvent(
                "turn-1",
                java.time.Instant.EPOCH,
                new minicode.context.stats.ContextStats(
                        minicode.context.accounting.TokenAccountingResult.estimateOnly(42, java.util.Optional.empty()),
                        100,
                        10,
                        90,
                        0.466,
                        minicode.context.stats.ContextWarningLevel.OK
                )
        ));
        sink.onEvent(new AgentEvent.AwaitUserEvent("turn-1", java.time.Instant.EPOCH, "tool-1", "Need input"));
        sink.onEvent(new AgentEvent.TurnCancelledEvent("turn-1", java.time.Instant.EPOCH,
                new TurnCancellation(CancellationSource.USER, CancellationPhase.TOOL_EXECUTION, "stop")));

        String text = output.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("context:"));
        assertTrue(text.contains("await_user: tool-1 Need input"));
        assertTrue(text.contains("cancelled: source=USER phase=TOOL_EXECUTION reason=stop"));
    }

    @Test
    void eventSinkDoesNotTreatOrdinaryToolErrorsAsDenyFeedback() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MiniTuiEventSink sink = new MiniTuiEventSink(output, event -> {
        });

        sink.onEvent(new AgentEvent.AssistantMessageEvent(
                "turn-1",
                java.time.Instant.EPOCH,
                new ToolResultMessage("tool-1", "run_command", "EXIT_CODE: 1", true)
        ));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("tool_error: EXIT_CODE: 1"));
        assertFalse(text.contains("deny_feedback:"));
    }

    @Test
    void eventSinkPrintsPermissionDeniedFeedbackSeparately() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MiniTuiEventSink sink = new MiniTuiEventSink(output, event -> {
        });

        sink.onEvent(new AgentEvent.AssistantMessageEvent(
                "turn-1",
                java.time.Instant.EPOCH,
                new ToolResultMessage("tool-1", "run_command", "Permission denied: Use mvn test instead", true)
        ));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("tool_error: Permission denied: Use mvn test instead"));
        assertTrue(text.contains("deny_feedback: Use mvn test instead"));
    }

    @Test
    void permissionDenyFeedbackIsVisibleAndReturnedToModel() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DenyFeedbackModelAdapter model = new DenyFeedbackModelAdapter();
        Path workspace = tempDir.resolve("workspace");
        assertDoesNotThrow(() -> java.nio.file.Files.createDirectory(workspace));
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                workspace,
                "session-1",
                model,
                new MiniTuiEventSink(output, event -> {
                }),
                PermissionPromptHandler.deny(PermissionDecision.DENY_WITH_FEEDBACK, "Use mvn test instead")
        );
        MiniTui tui = new MiniTui(services, input("run shell\n"), output);

        tui.runOnce();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("tool_result: run_command error"), text);
        assertTrue(text.contains("deny_feedback: Use mvn test instead"), text);
        assertTrue(text.contains("assistant: saw deny feedback"), text);
        assertTrue(model.sawFeedback, text);
    }

    @Test
    void tuiPersistsOnlyTurnPersistencePlanActionsAfterUserMessage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(messages -> new AssistantStep("progress", AssistantKind.PROGRESS),
                output, event -> {
                });
        MiniTui tui = new MiniTui(services, input("hello\n"), output, 8);

        tui.runOnce();

        List<SessionEvent> events = services.sessionStore().readAll("session-1", tempDir.resolve("workspace").toString());
        assertEquals(17, events.size());
        assertInstanceOf(UserMessage.class, events.get(0).message().orElseThrow());
        assertEquals("progress", ((minicode.core.message.AssistantProgressMessage) events.get(1).message().orElseThrow()).content());
        assertEquals(
                "Continue immediately from your <progress> update with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.",
                ((UserMessage) events.get(2).message().orElseThrow()).content()
        );
        assertTrue(events.stream().noneMatch(event -> event.message().orElse(null) instanceof SystemMessage));
    }

    private ApplicationServices services(ModelAdapter model, ByteArrayOutputStream output, AgentEventSink extraSink) {
        return ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                model,
                new MiniTuiEventSink(output, extraSink),
                PermissionPromptHandler.unavailable()
        );
    }

    private void assertStopReason(ModelAdapter model, String stopReason) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(model, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("turn_stop: " + stopReason));
    }

    private void assertStopDetail(ModelAdapter model, String expectedText) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = services(model, output, event -> {
        });
        MiniTui tui = new MiniTui(services, input("hello\n"), output);

        tui.runOnce();

        assertTrue(output.toString(StandardCharsets.UTF_8).contains(expectedText));
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingSink implements AgentEventSink {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingModelAdapter implements ModelAdapter {
        private final List<List<ChatMessage>> calls = new ArrayList<>();

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            return new AssistantStep("reply-" + calls.size(), AssistantKind.FINAL);
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
                return steps.getLast();
            }
            return steps.get(index++);
        }
    }

    private static final class DenyFeedbackModelAdapter implements ModelAdapter {
        private int calls;
        private boolean sawFeedback;

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls++;
            if (calls == 1) {
                return new ToolCallsStep(
                        List.of(new ToolCall("tool-1", "run_command",
                                JsonNodeFactory.instance.objectNode()
                                        .put("command", "powershell")
                                        .set("args", JsonNodeFactory.instance.arrayNode()
                                                .add("-NoProfile")
                                                .add("-Command")
                                                .add("Write-Output hi")))),
                        java.util.Optional.empty(),
                        ContentKind.UNSPECIFIED,
                        List.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty()
                );
            }
            sawFeedback = messages.stream()
                    .filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast)
                    .anyMatch(message -> message.error() && message.content().contains("Use mvn test instead"));
            return new AssistantStep(sawFeedback ? "saw deny feedback" : "missing feedback", AssistantKind.FINAL);
        }
    }
}
