package minicode.tui;

import minicode.app.ApplicationServices;
import minicode.core.event.AgentEvent;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.loop.ModelAdapter;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.model.MockModelAdapter;
import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionRequest;
import minicode.permissions.model.PermissionRequestDetails;
import minicode.permissions.model.PermissionRequestKind;
import minicode.permissions.model.PermissionResource;
import minicode.permissions.model.PermissionScope;
import minicode.session.factory.SessionEventFactory;
import minicode.session.store.SessionStore;
import minicode.tui.input.ScriptedTuiInput;
import minicode.tui.input.TuiInputEvent;
import minicode.tui.terminal.FakeTerminalScreen;
import minicode.tui.terminal.TerminalSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererTuiShellTest {
    @TempDir
    Path tempDir;

    @Test
    void turnUsesRendererScreenForTransientThinkingAndClearsStatusAfterFinal() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(40, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                messages -> {
                    assertTrue(screen.latestText().contains("Thinking..."));
                    return new AssistantStep("final answer", AssistantKind.FINAL);
                },
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("hello\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertTrue(latest.contains("user: hello"), latest);
        assertTrue(latest.contains("final answer"), latest);
        assertEquals(1, countOccurrences(latest, "user: hello"), latest);
        assertFalse(latest.contains("Thinking..."), latest);
        assertFalse(latest.contains("turn_stop: FINAL"), latest);
        assertFalse(latest.contains("status: Thinking..."), latest);
    }

    @Test
    void rendererShellStillSupportsSlashCompactAsLocalCommand() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(60, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("unused"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("/compact\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();

        String latest = screen.latestText();
        assertTrue(latest.contains("compact: started"), latest);
        assertTrue(latest.contains("compact: skipped"), latest);
        assertFalse(latest.contains("user: /compact"), latest);
    }

    @Test
    void startupProjectsExistingSessionHistoryIntoTranscript() {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        SessionEventFactory factory = new SessionEventFactory("session-1", workspace.toString());
        store.append(factory.message(new UserMessage("old question")));
        store.append(factory.message(new AssistantMessage("old answer")));
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(60, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                new MockModelAdapter("unused"),
                bridge,
                bridge
        );

        new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader(""))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        String latest = screen.latestText();
        assertTrue(latest.contains("user: old question"), latest);
        assertTrue(latest.contains("old answer"), latest);
    }

    @Test
    void localScrollCommandMovesTranscriptViewportToOlderHistory() {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        SessionEventFactory factory = new SessionEventFactory("session-1", workspace.toString());
        store.append(factory.message(new UserMessage("old-1")));
        store.append(factory.message(new AssistantMessage("old-2")));
        store.append(factory.message(new UserMessage("new-1")));
        store.append(factory.message(new AssistantMessage("new-2")));
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(40, 6));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                new MockModelAdapter("unused"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("/scroll-up\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();

        String latest = screen.latestText();
        assertTrue(latest.contains("user: old-1"), latest);
        assertTrue(latest.contains("old-2"), latest);
        assertFalse(latest.contains("new-2"), latest);
    }

    @Test
    void pageUpEventMovesTranscriptViewportWithoutSubmittingInput() {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        SessionEventFactory factory = new SessionEventFactory("session-1", workspace.toString());
        store.append(factory.message(new UserMessage("old-1")));
        store.append(factory.message(new AssistantMessage("old-2")));
        store.append(factory.message(new UserMessage("new-1")));
        store.append(factory.message(new AssistantMessage("new-2")));
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(40, 6));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                new MockModelAdapter("unused"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new ScriptedTuiInput(List.of(TuiInputEvent.pageUp())),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();

        String latest = screen.latestText();
        assertTrue(latest.contains("user: old-1"), latest);
        assertTrue(latest.contains("old-2"), latest);
        assertFalse(latest.contains("new-2"), latest);
    }

    @Test
    void mouseWheelUpEventMovesTranscriptViewportWithoutUsingInputHistory() {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        SessionEventFactory factory = new SessionEventFactory("session-1", workspace.toString());
        store.append(factory.message(new UserMessage("old mouse")));
        store.append(factory.message(new AssistantMessage("old answer")));
        store.append(factory.message(new UserMessage("new mouse")));
        store.append(factory.message(new AssistantMessage("new answer")));
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(44, 6));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                new MockModelAdapter("unused"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new ScriptedTuiInput(List.of(TuiInputEvent.scrollUp())),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();

        String latest = screen.latestText();
        assertTrue(latest.contains("user: old mouse"), latest);
        assertTrue(latest.contains("old answer"), latest);
        assertFalse(latest.contains("new answer"), latest);
    }

    @Test
    void characterEventsUpdateRendererOwnedInputCursorBeforeSubmit() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(60, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("done"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new ScriptedTuiInput(List.of(TuiInputEvent.character('h'), TuiInputEvent.character('i'))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.runOnce();

        String latest = screen.latestText();
        assertTrue(latest.contains("mini-code> hi"), latest);
        assertFalse(latest.contains("|"), latest);
    }

    @Test
    void leftAndRightEventsMoveRendererOwnedInputCursorWithoutTypingEscapeBytes() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(60, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("done"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new ScriptedTuiInput(List.of(
                        TuiInputEvent.character('a'),
                        TuiInputEvent.character('b'),
                        TuiInputEvent.character('c'),
                        TuiInputEvent.cursorLeft(),
                        TuiInputEvent.cursorLeft(),
                        TuiInputEvent.character('X'),
                        TuiInputEvent.cursorRight(),
                        TuiInputEvent.character('Y')
                )),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        for (int index = 0; index < 8; index++) {
            shell.runOnce();
        }

        String latest = screen.latestText();
        assertTrue(latest.contains("mini-code> aXbYc"), latest);
        assertFalse(latest.contains("OAOA"), latest);
        assertFalse(latest.contains("ODOC"), latest);
    }

    @Test
    void askUserEventCreatesQuestionBlockAndAwaitingAnswerMode() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(70, 10));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                messages -> new ToolCallsStep(
                        List.of(new minicode.tools.api.ToolCall("tool-1", "ask_user",
                                JsonNodeFactory.instance.objectNode().put("question", "Need a filename"))),
                        java.util.Optional.empty(),
                        ContentKind.UNSPECIFIED,
                        List.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty()
                ),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("start\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertTrue(latest.contains("ask_user: Need a filename"), latest);
        assertTrue(latest.contains("answer> "), latest);
        assertFalse(latest.contains("answer> |"), latest);
        assertEquals(1, countOccurrences(latest, "Need a filename"), latest);
        assertFalse(latest.contains("turn_stop: FINAL"), latest);
    }

    @Test
    void toolStartedAndFinishedUpdateSameToolUseBlock() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(80, 12));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                MockModelAdapter.toolThenFinal(
                        new minicode.tools.api.ToolCall("tool-1", "ask_user",
                                JsonNodeFactory.instance.objectNode().put("question", "Need input")),
                        "done"
                ),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("run\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertEquals(1, countOccurrences(latest, "tool: ask_user"), latest);
        assertTrue(latest.contains("tool: ask_user ok"), latest);
    }

    @Test
    void toolResultPreservesTargetSummaryFromToolStart() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);
        java.nio.file.Files.writeString(workspace.resolve("demo.txt"), "hello", java.nio.charset.StandardCharsets.UTF_8);
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(90, 20));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                workspace,
                "session-1",
                MockModelAdapter.toolThenFinal(
                        new minicode.tools.api.ToolCall("tool-1", "read_file",
                                JsonNodeFactory.instance.objectNode().put("path", "demo.txt")),
                        "done"
                ),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("read it\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertEquals(1, countOccurrences(latest, "tool: read_file"), latest);
        assertTrue(latest.contains("tool: read_file ok"), latest);
        assertTrue(latest.contains("path=demo.txt"), latest);
    }

    @Test
    void contextStatsEventUpdatesBadgeWithoutTranscriptNoise() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(70, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                messages -> {
                    bridge.onEvent(new AgentEvent.ContextStatsEvent(
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
                    return new AssistantStep("done", AssistantKind.FINAL);
                },
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("hello\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertTrue(latest.contains("context 47% normal 42/90 estimate"), latest);
        assertFalse(latest.contains("context:"), latest);
    }

    @Test
    void contextStatsBadgeShowsProviderSourceAndEffectiveInputDenominator() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(90, 8));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                messages -> {
                    bridge.onEvent(new AgentEvent.ContextStatsEvent(
                            "turn-1",
                            java.time.Instant.EPOCH,
                            new minicode.context.stats.ContextStats(
                                    minicode.context.accounting.TokenAccountingResult.providerUsage(
                                            56_000,
                                            138,
                                            56_138,
                                            new minicode.context.accounting.UsageBoundary(1, Optional.empty())
                                    ),
                                    1_048_576,
                                    16_000,
                                    1_032_576,
                                    0.054,
                                    minicode.context.stats.ContextWarningLevel.NORMAL
                            )
                    ));
                    return new AssistantStep("done", AssistantKind.FINAL);
                },
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("hello\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertTrue(latest.contains("context 5% normal 56k/1.0M provider"), latest);
    }

    @Test
    void rendererPermissionPromptDoesNotWriteToRendererStdoutAndLeavesAuditSummary() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(90, 20));
        RendererTuiBridge bridge = new RendererTuiBridge();
        Path workspace = tempDir.resolve("workspace");
        assertTrue(workspace.toFile().mkdirs());
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                workspace,
                "session-1",
                new DenyFeedbackModelAdapter(),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("run shell\ndeny_feedback\nUse mvn test instead\n"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );

        shell.runOnce();
        waitUntil(() -> screen.latestText().contains("permission>"), Duration.ofSeconds(2));
        String pending = screen.latestText();
        assertTrue(pending.contains("permission: pending"), pending);
        assertTrue(pending.contains("[1]"), pending);
        assertTrue(pending.contains("deny_feedback"), pending);
        shell.runOnce();
        waitUntil(() -> screen.latestText().contains("feedback>"), Duration.ofSeconds(2));
        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertTrue(latest.contains("permission: denied deny_feedback"), latest);
        assertTrue(latest.contains("tool: run_command error"), latest);
        assertFalse(latest.contains("choices:"), latest);
        assertFalse(latest.contains("[1] allow_once"), latest);
        assertFalse(latest.contains("Permission choice:"), latest);
        assertFalse(latest.contains("Waiting for permission choice."), latest);
    }

    @Test
    void editPermissionPendingShowsDiffPreviewBeforeApproval() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(100, 24));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("unused"),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new ScriptedTuiInput(List.of(TuiInputEvent.submitLine("allow_once"))),
                screen,
                MiniTui.DEFAULT_MAX_STEPS,
                bridge
        );
        PermissionRequest request = new PermissionRequest(
                "edit-request-1",
                PermissionRequestKind.EDIT,
                new PermissionResource.PathResource(Path.of("SnakeGame.java"), PathIntent.WRITE),
                "Allow file edit",
                new PermissionRequestDetails(
                        "Edit review",
                        "Review the proposed file change before it is applied.",
                        List.of(
                                "Path: SnakeGame.java",
                                "Operation: EDIT",
                                "Summary: Replace text in SnakeGame.java " + "x".repeat(220),
                                "Before chars: 53899",
                                "After chars: 54239",
                                "Preview truncated: false",
                                "Diff preview:",
                                "-oldLine();",
                                "+newLine();"
                        )
                ),
                List.of(
                        PermissionChoice.allowOnce("allow_once", "Allow this edit"),
                        PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
                ),
                true,
                PermissionScope.ONCE,
                new PermissionContext("session-1", java.util.Optional.of("turn-1"), java.util.Optional.of("tool-1"))
        );

        java.util.concurrent.CompletableFuture<?> prompt = java.util.concurrent.CompletableFuture.runAsync(
                () -> shell.requestPermission(request));
        waitUntil(() -> screen.latestText().contains("permission>"), Duration.ofSeconds(2));

        String pending = screen.latestText();
        assertTrue(pending.contains("permission: pending Edit review"), pending);
        assertTrue(pending.contains("Diff preview:"), pending);
        assertTrue(pending.contains("+newLine();"), pending);
        assertTrue(pending.contains("[1] allow_once"), pending);

        shell.runOnce();
        prompt.join();
    }

    @Test
    void nonFinalStopReasonIsVisibleButFinalIsNot() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(70, 10));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                messages -> new AssistantStep("progress only", AssistantKind.PROGRESS),
                bridge,
                bridge
        );
        RendererTuiShell shell = new RendererTuiShell(
                services,
                new BufferedLineInput(new BufferedReader(new StringReader("hello\n"))),
                screen,
                1,
                bridge
        );

        shell.runOnce();
        shell.awaitIdle(Duration.ofSeconds(2));

        String latest = screen.latestText();
        assertTrue(latest.contains("turn_stop: MAX_STEPS"), latest);
        assertFalse(latest.contains("turn_stop: FINAL"), latest);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        assertTrue(condition.getAsBoolean());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class DenyFeedbackModelAdapter implements ModelAdapter {
        private int calls;

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls++;
            if (calls == 1) {
                return new ToolCallsStep(
                        List.of(new minicode.tools.api.ToolCall("tool-1", "run_command",
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
            boolean sawFeedback = messages.stream()
                    .filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast)
                    .anyMatch(message -> message.error() && message.content().contains("Use mvn test instead"));
            return new AssistantStep(sawFeedback ? "saw deny feedback" : "missing feedback", AssistantKind.FINAL);
        }
    }
}
