package minicode.app;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.context.compact.CompactMetadata;
import minicode.context.compact.CompactTrigger;
import minicode.session.factory.SessionEventFactory;
import minicode.session.model.ForkDraft;
import minicode.session.model.RenameDraft;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.store.SessionStore;
import minicode.tools.api.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniCodeAppTest {
    @TempDir
    Path tempDir;

    @Test
    void helpPrintsUsageWithoutProviderConfigurationOrServices() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.run(
                new String[]{"--help"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(text.contains("Usage:"), text);
        assertTrue(text.contains("minicode"), text);
        assertTrue(text.contains("minicode --tty"), text);
        assertTrue(text.contains("minicode --cwd <path>"), text);
        assertTrue(text.contains("minicode --resume <id>"), text);
        assertTrue(text.contains("minicode --fork <id>"), text);
        assertTrue(text.contains("minicode session list"), text);
        assertTrue(text.contains("minicode session rename <id> <title>"), text);
        assertTrue(text.contains("minicode --max-steps <n>"), text);
        assertTrue(text.contains("minicode --ui-stdio-run"), text);
        assertTrue(text.contains("minicode --version"), text);
        assertTrue(text.contains("minicode --help"), text);
        assertFalse(text.contains("--snake"), text);
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("Configuration error:"), error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void versionPrintsStableVersionWithoutProviderConfigurationOrServices() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.run(
                new String[]{"--version"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        String text = output.toString(StandardCharsets.UTF_8).strip();
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertEquals("minicode 0.1.0-SNAPSHOT", text);
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("Configuration error:"), error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void uiStdioMockPrintsJsonlWithoutProviderConfigurationOrServices() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);

        int exitCode = MiniCodeApp.run(
                new String[]{"--ui-stdio-mock", "--cwd", workspace.toString()},
                tempDir.resolve("home"),
                tempDir.resolve("process-cwd"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        String[] lines = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(error.toString(StandardCharsets.UTF_8).isBlank(), error.toString(StandardCharsets.UTF_8));
        assertTrue(lines.length >= 8, output.toString(StandardCharsets.UTF_8));
        assertEquals("ready", new com.fasterxml.jackson.databind.ObjectMapper().readTree(lines[0]).get("type").asText());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"type\":\"permission_request\""));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"type\":\"await_user\""));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"type\":\"turn_stop\""));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(workspace.toString().replace("\\", "\\\\")));
    }

    @Test
    void uiStdioMockRunDoesNotLoadRealProviderConfig() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);

        int exitCode = MiniCodeApp.run(
                new String[]{"--ui-stdio-mock-run", "--cwd", workspace.toString()},
                tempDir.resolve("home"),
                tempDir.resolve("process-cwd"),
                new ByteArrayInputStream("{\"type\":\"shutdown\"}\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("Configuration error:"), error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void uiStdioRunIsExplicitRealProviderEntryAndLoadsRuntimeConfig() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);

        int exitCode = MiniCodeApp.run(
                new String[]{"--ui-stdio-run", "--cwd", workspace.toString()},
                tempDir.resolve("home"),
                tempDir.resolve("process-cwd"),
                new ByteArrayInputStream("{\"type\":\"shutdown\"}\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        String errorText = error.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode);
        assertTrue(errorText.contains("Configuration error:"), errorText);
        assertTrue(errorText.contains("No model configured."), errorText);
        assertFalse(errorText.contains("ANTHROPIC_AUTH_TOKEN="), errorText);
    }

    @Test
    void snakeEasterEggStartsWithoutProviderConfigurationOrServices() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        AtomicBoolean launched = new AtomicBoolean(false);

        int exitCode = MiniCodeApp.run(
                new String[]{"--snake"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible"),
                () -> launched.set(true)
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(launched.get());
        assertTrue(text.contains("Starting SnakeGame"), text);
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("Configuration error:"), error.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runReturnsClearErrorWhenRealProviderConfigIsMissing() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.run(
                new String[]{"session-1"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        String errorText = error.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode);
        assertTrue(errorText.contains("Configuration error:"));
        assertTrue(errorText.contains("No model configured."));
        assertTrue(errorText.contains("MINICODE_MODEL"));
        assertFalse(errorText.contains("ANTHROPIC_AUTH_TOKEN="));
    }

    @Test
    void runUsesOneReaderForTuiAndPermissionPrompt() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        DenyFeedbackModelAdapter model = new DenyFeedbackModelAdapter();
        Path workspace = tempDir.resolve("workspace");
        assertTrue(workspace.toFile().mkdirs());

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"session-1"},
                tempDir.resolve("home"),
                workspace,
                new ByteArrayInputStream("""
                        run shell
                        deny_feedback
                        Use mvn test instead
                        quit
                        """.getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                (home, cwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        home,
                        cwd,
                        sessionId,
                        model,
                        eventSink,
                        permissionPromptHandler
                )
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(text.contains("permission: Command execution"), text);
        assertTrue(text.contains("deny_feedback: Use mvn test instead"), text);
        assertTrue(text.contains("assistant: saw deny feedback"), text);
        assertTrue(model.sawFeedback, text);
    }

    @Test
    void runReturnsClearRuntimeErrorForUnexpectedAppFailure() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"session-1"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                (home, cwd, sessionId, eventSink, permissionPromptHandler) -> {
                    throw new IllegalStateException("session store failed");
                }
        );

        String errorText = error.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode);
        assertTrue(errorText.contains("Runtime error: session store failed"));
        assertFalse(errorText.contains("IllegalStateException"));
        assertFalse(errorText.contains("ANTHROPIC_AUTH_TOKEN"));
    }

    @Test
    void sessionListCommandPrintsSessionsForCurrentCwd() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        store.append(new SessionEventFactory("session-1", workspace.toString()).message(new UserMessage("hello title")));

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"session", "list"},
                home,
                workspace,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(text.contains("session-1"), text);
        assertTrue(text.contains("hello title"), text);
        assertTrue(text.contains(workspace.toString()), text);
    }

    @Test
    void sessionListCommandPrintsHeaderAndTruncatesLongTitles() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        String longTitle = "请用 run_command 执行 cmd /c where mvn，确认当前环境能找到 Maven。只运行这一条命令，然后用中文总结结果。";
        SessionStore store = new SessionStore(home.resolve("sessions"));
        store.append(new SessionEventFactory("session-long", workspace.toString()).message(new UserMessage(longTitle)));

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"session", "list"},
                home,
                workspace,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        String text = output.toString(StandardCharsets.UTF_8);
        String[] lines = text.strip().split("\\R");
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(lines[0].startsWith("SESSION ID"), text);
        assertTrue(lines[0].contains("TITLE"), text);
        assertTrue(lines[0].contains("UPDATED"), text);
        assertTrue(lines[0].contains("CWD"), text);
        assertTrue(lines[1].contains("session-long"), text);
        assertTrue(lines[1].contains("..."), text);
        assertFalse(lines[1].contains(longTitle), text);
    }

    @Test
    void publicRunSessionListDoesNotRequireProviderConfiguration() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.run(
                new String[]{"session", "list"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace").toAbsolutePath().normalize(),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of("MINICODE_PROVIDER", "anthropic-compatible")
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("No sessions for cwd:"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("Configuration error:"));
    }

    @Test
    void defaultCwdUsesCurrentProcessDirectoryForServicesWorkspace() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path processCwd = tempDir.resolve("process-cwd").toAbsolutePath().normalize();
        Files.createDirectories(processCwd);
        List<Path> capturedCwds = new ArrayList<>();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"session-1"},
                tempDir.resolve("home"),
                processCwd,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                (home, cwd, sessionId, eventSink, permissionPromptHandler) -> {
                    capturedCwds.add(cwd);
                    return ApplicationServices.create(home, cwd, sessionId,
                            messages -> new AssistantStep("ok", AssistantKind.FINAL),
                            eventSink,
                            permissionPromptHandler);
                }
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(processCwd), capturedCwds);
    }

    @Test
    void cwdOptionControlsServicesWorkspaceCwd() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path processCwd = tempDir.resolve("process-cwd");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(processCwd);
        Files.createDirectories(workspace);
        List<Path> capturedCwds = new ArrayList<>();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--cwd", workspace.toString(), "session-1"},
                tempDir.resolve("home"),
                processCwd,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                (home, cwd, sessionId, eventSink, permissionPromptHandler) -> {
                    capturedCwds.add(cwd);
                    return ApplicationServices.create(home, cwd, sessionId,
                            messages -> new AssistantStep("ok", AssistantKind.FINAL),
                            eventSink,
                            permissionPromptHandler);
                }
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(workspace), capturedCwds);
    }

    @Test
    void cwdOptionMustPointToExistingDirectory() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path missing = tempDir.resolve("missing");

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--cwd", missing.toString(), "session-1"},
                tempDir.resolve("home"),
                tempDir.resolve("process-cwd"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("--cwd must be an existing directory"));
    }

    @Test
    void cwdOptionControlsSessionListWorkspace() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path home = tempDir.resolve("home");
        Path processCwd = tempDir.resolve("process-cwd").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(processCwd);
        Files.createDirectories(workspace);
        SessionStore store = new SessionStore(home.resolve("sessions"));
        store.append(new SessionEventFactory("session-override", workspace.toString()).message(new UserMessage("override title")));
        store.append(new SessionEventFactory("session-process", processCwd.toString()).message(new UserMessage("process title")));

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--cwd", workspace.toString(), "session", "list"},
                home,
                processCwd,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(text.contains("session-override"), text);
        assertFalse(text.contains("session-process"), text);
        assertTrue(text.contains(workspace.toString()), text);
    }

    @Test
    void publicRunLoadsRuntimeConfigFromCwdOverride() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path processCwd = tempDir.resolve("process-cwd");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(processCwd);
        Files.createDirectories(workspace.resolve(".minicode"));
        Files.writeString(workspace.resolve(".minicode").resolve("settings.json"),
                "{\"provider\":\"mock\",\"model\":\"mock-model\"}", StandardCharsets.UTF_8);

        int exitCode = MiniCodeApp.run(
                new String[]{"--cwd", workspace.toString(), "session-1"},
                tempDir.resolve("home"),
                processCwd,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                Map.of()
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains("Configuration error:"));
    }

    @Test
    void maxStepsOptionControlsTuiTurnBudget() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--max-steps", "1", "session-1"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream("hello\nquit\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                (home, cwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        home,
                        cwd,
                        sessionId,
                        new SequenceModelAdapter(
                                new AssistantStep("progress", AssistantKind.PROGRESS),
                                new AssistantStep("done", AssistantKind.FINAL)
                        ),
                        eventSink,
                        permissionPromptHandler
                )
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertTrue(text.contains("turn_stop: MAX_STEPS"), text);
        assertFalse(text.contains("assistant: done"), text);
    }

    @Test
    void effectiveMaxStepsUsesSettingsWhenCliOptionIsAbsent() {
        minicode.config.RuntimeConfig runtimeConfig = new minicode.config.RuntimeConfig(
                minicode.config.ProviderKind.MOCK,
                "mock-model",
                "https://example.invalid",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(48),
                java.time.Duration.ofSeconds(300),
                "test",
                Map.of()
        );

        int maxSteps = MiniCodeApp.effectiveMaxSteps(Optional.empty(), Optional.of(runtimeConfig));

        assertEquals(48, maxSteps);
    }

    @Test
    void effectiveMaxStepsPrefersCliOverSettings() {
        minicode.config.RuntimeConfig runtimeConfig = new minicode.config.RuntimeConfig(
                minicode.config.ProviderKind.MOCK,
                "mock-model",
                "https://example.invalid",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(1),
                java.time.Duration.ofSeconds(300),
                "test",
                Map.of()
        );

        int maxSteps = MiniCodeApp.effectiveMaxSteps(Optional.of(2), Optional.of(runtimeConfig));

        assertEquals(2, maxSteps);
    }

    @Test
    void maxStepsOptionRejectsInvalidValues() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--max-steps", "0", "session-1"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("--max-steps must be between 1 and 100"));
    }

    @Test
    void optionValuesMayStartWithDashAndAreValidatedBySpecificOption() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--max-steps", "-1", "session-1"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        String errorText = error.toString(StandardCharsets.UTF_8);
        assertEquals(1, exitCode);
        assertTrue(errorText.contains("--max-steps must be between 1 and 100"), errorText);
        assertFalse(errorText.contains("Missing value for --max-steps"), errorText);
    }

    @Test
    void sessionRenameCommandWritesRenameMetaEvent() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        store.append(new SessionEventFactory("session-1", workspace.toString()).message(new UserMessage("old title")));

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"session", "rename", "session-1", "new", "title"},
                home,
                workspace,
                new ByteArrayInputStream(new byte[0]),
                output,
                error,
                failingFactory()
        );

        var events = store.readAll("session-1", workspace.toString());
        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertEquals(new RenameDraft("new title"), events.getLast().meta().orElseThrow());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Renamed session session-1"));
    }

    @Test
    void resumeArgumentStartsTurnFromLatestCompactBoundaryReplay() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        ContextSummaryMessage summary = new ContextSummaryMessage("compact summary", 2, Instant.EPOCH);
        new SessionPersistenceRunner(store, new SessionEventFactory("session-1", workspace.toString()))
                .apply(new TurnPersistencePlan(List.of(
                        new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("old"))),
                        new PersistenceAction.AppendCompactBoundaryAction(summary,
                                new CompactMetadata(CompactTrigger.MANUAL, 100, 20, 2, Instant.EPOCH)),
                        new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("new")))
                )));
        ReplayCheckingModelAdapter model = new ReplayCheckingModelAdapter();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--resume", "session-1"},
                home,
                workspace,
                new ByteArrayInputStream("continue\nquit\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                (actualHome, cwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        actualHome,
                        cwd,
                        captureSessionId(model, sessionId),
                        model,
                        eventSink,
                        permissionPromptHandler
                )
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertEquals("session-1", model.sessionId);
        assertTrue(model.sawSummary, model.roles);
        assertTrue(model.sawNew, model.roles);
        assertFalse(model.sawOld, model.roles);
        var events = store.readAll("session-1", workspace.toString());
        int userIndex = events.size() - 2;
        assertEquals(new UserMessage("continue"), events.get(userIndex).message().orElseThrow());
        assertEquals(Optional.of(events.get(userIndex - 1).uuid()), events.get(userIndex).parentUuid());
    }

    @Test
    void forkArgumentCreatesForkWithLineageAndStartsForkedSession() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore store = new SessionStore(home.resolve("sessions"));
        store.append(new SessionEventFactory("source", workspace.toString()).message(new UserMessage("source title")));
        CapturingModelAdapter model = new CapturingModelAdapter();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--fork", "source"},
                home,
                workspace,
                new ByteArrayInputStream("continue\nquit\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                (actualHome, cwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        actualHome,
                        cwd,
                        captureSessionId(model, sessionId),
                        model,
                        eventSink,
                        permissionPromptHandler
                )
        );

        assertEquals(0, exitCode, error.toString(StandardCharsets.UTF_8));
        assertFalse("source".equals(model.sessionId));
        var forkedEvents = store.readAll(model.sessionId, workspace.toString());
        ForkDraft forkDraft = assertInstanceOf(ForkDraft.class, forkedEvents.getFirst().meta().orElseThrow());
        assertEquals("source", forkDraft.metadata().sourceSessionId());
        assertEquals(model.sessionId, forkDraft.metadata().newSessionId());
    }

    @Test
    void resumeMissingSessionReturnsClearErrorBeforeStartingTui() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = MiniCodeApp.runWithServices(
                new String[]{"--resume", "missing"},
                tempDir.resolve("home"),
                tempDir.resolve("workspace").toAbsolutePath().normalize(),
                new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)),
                output,
                error,
                failingFactory()
        );

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Runtime error: Session not found: missing"));
    }

    @Test
    void appAndTuiDoNotParseJsonlDirectly() throws Exception {
        List<Path> files = List.of(
                Path.of("src/main/java/minicode/app/MiniCodeApp.java"),
                Path.of("src/main/java/minicode/tui/MiniTui.java")
        );

        for (Path file : files) {
            String text = java.nio.file.Files.readString(file);
            assertFalse(text.contains("readAllLines"), file.toString());
            assertFalse(text.contains(".jsonl"), file.toString());
            assertFalse(text.contains("ObjectMapper"), file.toString());
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

    private static MiniCodeApp.ServicesFactory failingFactory() {
        return (home, cwd, sessionId, eventSink, permissionPromptHandler) -> {
            throw new AssertionError("services should not be created for management command");
        };
    }

    private static String captureSessionId(CapturingModelAdapter model, String sessionId) {
        model.sessionId = sessionId;
        return sessionId;
    }

    private static class CapturingModelAdapter implements ModelAdapter {
        protected String sessionId;

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            return new AssistantStep("ok", AssistantKind.FINAL);
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

    private static final class ReplayCheckingModelAdapter extends CapturingModelAdapter {
        private boolean sawOld;
        private boolean sawNew;
        private boolean sawSummary;
        private String roles;

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            roles = messages.toString();
            sawOld = messages.contains(new UserMessage("old"));
            sawNew = messages.contains(new UserMessage("new"));
            sawSummary = messages.stream().anyMatch(ContextSummaryMessage.class::isInstance);
            return new AssistantStep("ok", AssistantKind.FINAL);
        }
    }
}
