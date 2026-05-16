package minicode.app;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.context.compact.CompactMetadata;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactPolicy;
import minicode.context.compact.CompactService;
import minicode.context.compact.CompactTrigger;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.context.accounting.TokenAccountingService;
import minicode.core.event.AgentEvent;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnStopReason;
import minicode.context.manager.ContextManager;
import minicode.core.loop.AgentLoop;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.model.MockModelAdapter;
import minicode.model.anthropic.AnthropicModelAdapter;
import minicode.model.anthropic.AnthropicModelsApiClient;
import minicode.model.anthropic.HttpAnthropicTransport;
import minicode.mcp.McpRuntime;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.store.PermissionStore;
import minicode.permissions.store.JsonPermissionStore;
import minicode.prompt.SystemPromptBuilder;
import minicode.session.factory.SessionEventFactory;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.store.SessionStore;
import minicode.skills.SkillRegistry;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import minicode.tools.result.ToolResultStorage;
import minicode.workspace.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationServicesTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void appAssemblyRegistersPhaseOneToolsAndPersistsViaRunner() {
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        ToolRegistry registry = services.toolRegistry();

        assertTrue(registry.find("ask_user").isPresent());
        assertTrue(registry.find("read_file").isPresent());
        assertTrue(registry.find("run_command").isPresent());
        assertTrue(registry.find("list_files").isPresent());
        assertTrue(registry.find("grep_files").isPresent());
        assertTrue(registry.find("write_file").isPresent());
        assertTrue(registry.find("edit_file").isPresent());
        assertTrue(registry.find("patch_file").isPresent());
        assertTrue(registry.find("modify_file").isPresent());
        assertTrue(registry.find("load_skill").isPresent());
        assertNotNull(services.permissionService());
        assertNotNull(services.contextManager());
        assertNotNull(services.sessionStore());
        assertNotNull(services.sessionPersistenceRunner());
        assertNotNull(services.agentLoop());
        assertNotNull(services.workspacePathResolver());
    }

    @Test
    void appAssemblyRegistersLoadSkillAndPromptListsDiscoveredSkills() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Path skillDir = workspace.resolve(".minicode/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# Review\n\nReview code carefully.");

        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        assertTrue(services.toolRegistry().find("load_skill").isPresent());
        AgentTurnRequest request = services.turnRequest(List.of(new UserMessage("hello")), 3);
        String systemPrompt = ((SystemMessage) request.messages().getFirst()).content();
        assertTrue(systemPrompt.contains("Available skills:"));
        assertTrue(systemPrompt.contains("- review: Review code carefully."));
        assertFalse(systemPrompt.contains("# Review"));
    }

    @Test
    void appAssemblyStartsWhenNoSkillsDirectoriesExist() {
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnRequest request = services.turnRequest(List.of(new UserMessage("hello")), 3);
        String systemPrompt = ((SystemMessage) request.messages().getFirst()).content();
        assertTrue(services.toolRegistry().find("load_skill").isPresent());
        assertTrue(systemPrompt.contains("Available skills:\n- none discovered"));
    }

    @Test
    void modelCanLoadDiscoveredSkillThroughToolRegistry() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Path skillDir = workspace.resolve(".minicode/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# Review\n\nReview code carefully.");

        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                MockModelAdapter.toolThenFinal(
                        new ToolCall(
                                "tool-use-1",
                                "load_skill",
                                JsonNodeFactory.instance.objectNode().put("name", "review")
                        ),
                        "loaded"
                ),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("use review skill")), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        ToolResultMessage toolResult = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("load_skill", toolResult.toolName());
        assertTrue(toolResult.content().contains("SKILL: review"));
        assertTrue(toolResult.content().contains("# Review"));
    }

    @Test
    void appAssemblyInjectsPermissionStoreUnderHome() {
        Path home = tempDir.resolve("home");
        ApplicationServices services = ApplicationServices.create(
                home,
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        assertInstanceOf(JsonPermissionStore.class, services.permissionStore());
        assertEquals(home.resolve("permissions.json"), services.permissionStorePath());
    }

    @Test
    void mockModelCompletesOneUserToAssistantFinalTurnThroughAssembly() {
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("mock final"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("hello")), 3));
        services.sessionPersistenceRunner().apply(result.persistencePlan());

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("mock final", assistant.content());
        assertFalse(services.sessionStore().readAll("session-1", tempDir.resolve("workspace").toString()).isEmpty());
    }

    @Test
    void appAssemblyResumesSessionParentChainFromExistingLastEvent() {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore preexistingStore = new SessionStore(home.resolve("sessions"));
        SessionEventFactory preexistingFactory = new SessionEventFactory("session-1", cwd.toString());
        var firstEvent = preexistingFactory.message(new UserMessage("already saved"));
        preexistingStore.append(firstEvent);
        ApplicationServices services = ApplicationServices.create(
                home,
                cwd,
                "session-1",
                new MockModelAdapter("resumed answer"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("continue")), 3));
        services.sessionPersistenceRunner().apply(result.persistencePlan());

        var events = services.sessionStore().readAll("session-1", cwd.toString());
        assertEquals(Optional.of(firstEvent.uuid()), events.get(1).parentUuid());
        assertEquals(Optional.of(firstEvent.uuid()), events.get(1).logicalParentUuid());
    }

    @Test
    void sessionMessagesReplayFromLatestCompactBoundary() {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        SessionStore preexistingStore = new SessionStore(home.resolve("sessions"));
        SessionPersistenceRunner runner = new SessionPersistenceRunner(
                preexistingStore,
                new SessionEventFactory("session-1", cwd.toString())
        );
        ContextSummaryMessage summary = new ContextSummaryMessage("summary", 2, Instant.EPOCH);
        runner.apply(new minicode.session.plan.TurnPersistencePlan(List.of(
                new minicode.session.plan.PersistenceAction.AppendMessagesAction(List.of(new UserMessage("old"))),
                new minicode.session.plan.PersistenceAction.AppendCompactBoundaryAction(
                        summary,
                        new CompactMetadata(CompactTrigger.AUTO, 100, 25, 2, Instant.EPOCH)
                ),
                new minicode.session.plan.PersistenceAction.AppendMessagesAction(List.of(new UserMessage("new")))
        )));
        ApplicationServices services = ApplicationServices.create(
                home,
                cwd,
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        List<ChatMessage> messages = services.sessionMessages();

        assertEquals(List.of(summary, new UserMessage("new")), messages);
    }

    @Test
    void manualCompactWritesBoundaryAndSummaryEvents() {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        ApplicationServices services = ApplicationServices.create(
                home,
                cwd,
                "session-1",
                messages -> new minicode.core.step.AssistantStep("manual summary", minicode.core.step.AssistantKind.FINAL),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );
        List<ChatMessage> initial = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            initial.add(new UserMessage("user-" + index + " " + "x".repeat(80)));
            initial.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(80)));
        }
        initial.add(new AssistantMessage("recent usage",
                Optional.of(new minicode.model.ProviderUsage(100, 10, 110)),
                minicode.model.UsageStaleness.fresh()));
        services.sessionPersistenceRunner().apply(new minicode.session.plan.TurnPersistencePlan(List.of(
                new minicode.session.plan.PersistenceAction.AppendMessagesAction(initial)
        )));

        var result = services.manualCompact();

        assertEquals(minicode.context.compact.CompactStatus.COMPACTED, result.status());
        var events = services.sessionStore().readAll("session-1", cwd.toString());
        int boundaryIndex = -1;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).type() == minicode.session.model.SessionEventType.COMPACT_BOUNDARY) {
                boundaryIndex = index;
            }
        }
        assertTrue(boundaryIndex >= 0);
        assertEquals(minicode.context.compact.CompactTrigger.MANUAL,
                events.get(boundaryIndex).compactMetadata().orElseThrow().trigger());
        ContextSummaryMessage summary = assertInstanceOf(ContextSummaryMessage.class,
                events.get(boundaryIndex + 1).message().orElseThrow());
        assertEquals("manual summary", summary.content());
        assertEquals(Optional.of(events.get(boundaryIndex).uuid()), events.get(boundaryIndex + 1).parentUuid());
    }

    @Test
    void manualCompactDoesNotWriteWhenSkipped() {
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                cwd,
                "session-1",
                new MockModelAdapter("unused"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        var result = services.manualCompact();

        assertEquals(minicode.context.compact.CompactStatus.SKIPPED, result.status());
        assertTrue(services.sessionStore().readAll("session-1", cwd.toString()).isEmpty());
    }

    @Test
    void sessionMessagesReplayFromManualCompactBoundaryAfterManualCompact() {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        ApplicationServices services = ApplicationServices.create(
                home,
                cwd,
                "session-1",
                messages -> new minicode.core.step.AssistantStep("manual summary", minicode.core.step.AssistantKind.FINAL),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );
        List<ChatMessage> initial = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            initial.add(new UserMessage("user-" + index + " " + "x".repeat(80)));
            initial.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(80)));
        }
        initial.add(new AssistantMessage("recent usage",
                Optional.of(new minicode.model.ProviderUsage(100, 10, 110)),
                minicode.model.UsageStaleness.fresh()));
        services.sessionPersistenceRunner().apply(new minicode.session.plan.TurnPersistencePlan(List.of(
                new minicode.session.plan.PersistenceAction.AppendMessagesAction(initial)
        )));

        services.manualCompact();
        services.sessionPersistenceRunner().apply(new minicode.session.plan.TurnPersistencePlan(List.of(
                new minicode.session.plan.PersistenceAction.AppendMessagesAction(List.of(new UserMessage("after compact")))
        )));

        List<ChatMessage> replay = services.sessionMessages();

        assertInstanceOf(ContextSummaryMessage.class, replay.getFirst());
        assertEquals("manual summary", ((ContextSummaryMessage) replay.getFirst()).content());
        assertTrue(replay.stream().anyMatch(message -> message instanceof UserMessage user
                && user.content().startsWith("user-")));
        assertTrue(replay.stream().anyMatch(message -> message instanceof AssistantMessage assistant
                && assistant.content().startsWith("assistant-")));
        AssistantMessage retainedUsage = replay.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> message.content().equals("recent usage"))
                .findFirst()
                .orElseThrow();
        assertTrue(retainedUsage.usageStaleness().stale());
        assertTrue(retainedUsage.usageStaleness().reason().orElseThrow().contains("manually compacted"));
        assertEquals(new UserMessage("after compact"), replay.getLast());
        assertTrue(replay.stream().noneMatch(message -> message instanceof SystemMessage));
    }

    @Test
    void autoCompactPersistencePlanReplaysFromLatestCompactBoundary() {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        AutoCompactRecordingModel model = new AutoCompactRecordingModel();
        ToolRegistry registry = new ToolRegistry();
        ContextManager contextManager = ContextManager.noOp();
        SessionStore sessionStore = new SessionStore(home.resolve("sessions"));
        ApplicationServices services = new ApplicationServices(
                registry,
                new TrackingPermissionService(),
                contextManager,
                sessionStore,
                new SessionPersistenceRunner(sessionStore, new SessionEventFactory("session-1", cwd.toString())),
                new AgentLoop(model, event -> {
                }, registry, contextManager,
                        new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_000, 100)),
                        new AutoCompactController(new CompactService(FIXED_CLOCK),
                                new AutoCompactPolicy(0.85d, 3, 0, 2))),
                model,
                new CompactService(FIXED_CLOCK),
                new SystemPromptBuilder(),
                new WorkspacePathResolver(),
                new SkillRegistry(List.of()),
                McpRuntime.empty(),
                PermissionStore.none(),
                home.resolve("permissions.json"),
                home,
                cwd,
                "session-1"
        );
        List<ChatMessage> initial = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            initial.add(new UserMessage("user-" + index + " " + "x".repeat(100)));
            initial.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(100)));
        }

        AgentTurnResult result = services.runTurn(services.turnRequest(initial, 2));
        services.sessionPersistenceRunner().apply(result.persistencePlan());

        List<ChatMessage> replay = services.sessionMessages();
        assertInstanceOf(ContextSummaryMessage.class, replay.getFirst());
        assertEquals("auto summary", ((ContextSummaryMessage) replay.getFirst()).content());
        assertTrue(replay.stream().anyMatch(message -> message instanceof UserMessage user
                && user.content().startsWith("user-")));
        assertEquals("done", ((AssistantMessage) replay.getLast()).content());
        assertTrue(services.sessionStore().readAll("session-1", cwd.toString()).stream()
                .anyMatch(event -> event.type() == minicode.session.model.SessionEventType.COMPACT_BOUNDARY
                        && event.compactMetadata().orElseThrow().trigger() == CompactTrigger.AUTO));
    }

    @Test
    void autoCompactMarksRetainedUsageWithAutoReason() {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        AutoCompactRecordingModel model = new AutoCompactRecordingModel();
        ToolRegistry registry = new ToolRegistry();
        ContextManager contextManager = ContextManager.noOp();
        SessionStore sessionStore = new SessionStore(home.resolve("sessions"));
        ApplicationServices services = new ApplicationServices(
                registry,
                new TrackingPermissionService(),
                contextManager,
                sessionStore,
                new SessionPersistenceRunner(sessionStore, new SessionEventFactory("session-1", cwd.toString())),
                new AgentLoop(model, event -> {
                }, registry, contextManager,
                        new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(1_000, 100)),
                        new AutoCompactController(new CompactService(FIXED_CLOCK),
                                new AutoCompactPolicy(0.85d, 3, 0, 2))),
                model,
                new CompactService(FIXED_CLOCK),
                new SystemPromptBuilder(),
                new WorkspacePathResolver(),
                new SkillRegistry(List.of()),
                McpRuntime.empty(),
                PermissionStore.none(),
                home.resolve("permissions.json"),
                home,
                cwd,
                "session-1"
        );
        List<ChatMessage> initial = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            initial.add(new UserMessage("user-" + index + " " + "x".repeat(100)));
            initial.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(100)));
        }
        initial.add(new AssistantMessage("recent usage",
                Optional.of(new minicode.model.ProviderUsage(100, 10, 110)),
                minicode.model.UsageStaleness.fresh()));
        initial.add(new UserMessage("tail pressure " + "z".repeat(4_000)));

        AgentTurnResult result = services.runTurn(services.turnRequest(initial, 2));

        AssistantMessage retainedUsage = result.messages().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> message.content().equals("recent usage"))
                .findFirst()
                .orElseThrow();
        assertTrue(retainedUsage.usageStaleness().stale());
        String reason = retainedUsage.usageStaleness().reason().orElseThrow();
        assertTrue(reason.contains("automatically compacted"), reason);
        assertFalse(reason.contains("manually compacted"), reason);
    }

    @Test
    void applicationRunTurnNotifiesPermissionTurnLifecycle() {
        TrackingPermissionService permissionService = new TrackingPermissionService();
        ToolRegistry registry = new ToolRegistry();
        ContextManager contextManager = new ContextManager(new ToolResultStorage(tempDir.resolve("tool-results")),
                20_000, 2_000);
        SessionStore sessionStore = new SessionStore(tempDir.resolve("sessions"));
        MockModelAdapter modelAdapter = new MockModelAdapter("done");
        ApplicationServices services = new ApplicationServices(
                registry,
                permissionService,
                contextManager,
                sessionStore,
                new SessionPersistenceRunner(sessionStore, new SessionEventFactory("session-1", tempDir.toString())),
                new AgentLoop(modelAdapter, event -> {
                }, registry, contextManager),
                modelAdapter,
                new CompactService(),
                new SystemPromptBuilder(),
                new WorkspacePathResolver(),
                new SkillRegistry(List.of()),
                McpRuntime.empty(),
                PermissionStore.none(),
                tempDir.resolve("permissions.json"),
                tempDir,
                tempDir,
                "session-1"
        );

        AgentTurnRequest request = services.turnRequest(List.of(new UserMessage("hello")), 3);
        AgentTurnResult result = services.runTurn(request);

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(List.of(request.turnId()), permissionService.begun);
        assertEquals(List.of(request.turnId()), permissionService.ended);
    }

    @Test
    void defaultTurnRequestUsesNonCancelledToken() {
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnRequest request = services.turnRequest(List.of(new UserMessage("hello")), 3);

        assertFalse(request.cancellationToken().isCancellationRequested());
    }

    @Test
    void turnRequestRefreshesSystemPromptBeforeUserMessages() {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "session-1",
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnRequest first = services.turnRequest(List.of(new UserMessage("hello")), 3);
        AgentTurnRequest second = services.turnRequest(List.of(new UserMessage("hello again")), 3);

        assertInstanceOf(minicode.core.message.SystemMessage.class, first.messages().getFirst());
        assertInstanceOf(UserMessage.class, first.messages().get(1));
        assertEquals(((minicode.core.message.SystemMessage) first.messages().getFirst()).content(),
                ((minicode.core.message.SystemMessage) second.messages().getFirst()).content());
        assertTrue(((minicode.core.message.SystemMessage) first.messages().getFirst()).content()
                .contains("Available tools:"));
        assertTrue(((minicode.core.message.SystemMessage) first.messages().getFirst()).content()
                .contains("run_command"));
    }

    @Test
    void createFromRuntimeConfigUsesMockOnlyWhenExplicitlySelected() {
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.MOCK,
                "mock-model",
                "mock://local",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "test"
        );

        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                config,
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("hello")), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("mock final", assistant.content());
    }

    @Test
    void createFromRuntimeConfigUsesAnthropicAdapterForRealProvider() {
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "claude-test",
                "http://127.0.0.1:9",
                Optional.of("key"),
                Optional.empty(),
                Optional.of(4096),
                Optional.of(200000),
                "test"
        );

        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                config,
                event -> {
                },
                PermissionPromptHandler.unavailable()
        );

        assertInstanceOf(AnthropicModelAdapter.class, services.modelAdapter());
    }

    @Test
    void createFromRuntimeConfigAppliesModelContextWindowToContextStats() {
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.MOCK,
                "claude-3-5-haiku-latest",
                "mock://local",
                Optional.empty(),
                Optional.empty(),
                Optional.of(512),
                Optional.of(200000),
                "test"
        );
        List<AgentEvent> events = new ArrayList<>();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                config,
                events::add,
                PermissionPromptHandler.unavailable()
        );

        services.runTurn(services.turnRequest(List.of(new UserMessage("hello")), 3));

        AgentEvent.ContextStatsEvent statsEvent = events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(200000, statsEvent.stats().contextWindow());
        assertEquals(512, statsEvent.stats().outputReserve());
        assertEquals(199488, statsEvent.stats().effectiveInput());
    }

    @Test
    void configuredMaxOutputTokensParticipatesInContextStatsOutputReserve() {
        List<AgentEvent> events = new ArrayList<>();
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.MOCK,
                "claude-sonnet-4",
                "https://example.test",
                Optional.empty(),
                Optional.empty(),
                Optional.of(64_000),
                Optional.of(200_000),
                "test"
        );
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                config,
                messages -> new minicode.core.step.AssistantStep("ok", minicode.core.step.AssistantKind.FINAL),
                events::add,
                PermissionPromptHandler.unavailable()
        );

        services.runTurn(services.turnRequest(List.of(new UserMessage("hello")), 1));

        AgentEvent.ContextStatsEvent stats = events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(200_000L, stats.stats().contextWindow());
        assertEquals(64_000L, stats.stats().outputReserve());
        assertEquals(136_000L, stats.stats().effectiveInput());
    }

    @Test
    void mockProviderDoesNotRequireModelsApiMetadataAndUsesMimoFallback() {
        List<AgentEvent> events = new ArrayList<>();
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.MOCK,
                "mimo-v2.5-pro",
                "https://example.test",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "test"
        );
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                "session-1",
                config,
                messages -> new minicode.core.step.AssistantStep("ok", minicode.core.step.AssistantKind.FINAL),
                events::add,
                PermissionPromptHandler.unavailable()
        );

        services.runTurn(services.turnRequest(List.of(new UserMessage("hello")), 1));

        AgentEvent.ContextStatsEvent stats = events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(1_048_576L, stats.stats().contextWindow());
        assertEquals(16_000L, stats.stats().outputReserve());
    }

    @Test
    void metadataLookupUsesShortTimeoutInsteadOfProviderRequestTimeout() throws Exception {
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "mimo-v2.5-pro",
                "https://example.test",
                Optional.empty(),
                Optional.of("token"),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(300),
                "test"
        );
        Method method = ApplicationServices.class.getDeclaredMethod("metadataClient", RuntimeConfig.class);
        method.setAccessible(true);

        AnthropicModelsApiClient client = (AnthropicModelsApiClient) method.invoke(null, config);

        HttpAnthropicTransport transport = assertInstanceOf(HttpAnthropicTransport.class, client.transport());
        assertEquals(Duration.ofSeconds(3), transport.timeout());
    }

    @Test
    void metadataLookupIsSkippedOnlyForMockProvider() throws Exception {
        RuntimeConfig mock = new RuntimeConfig(
                ProviderKind.MOCK,
                "mimo-v2.5-pro",
                "https://example.test",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(1_048_576),
                "test"
        );
        RuntimeConfig realWithContextWindow = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "mimo-v2.5-pro",
                "https://example.test",
                Optional.empty(),
                Optional.of("token"),
                Optional.empty(),
                Optional.of(1_048_576),
                "test"
        );
        Method method = ApplicationServices.class.getDeclaredMethod("shouldFetchModelMetadata", RuntimeConfig.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(null, mock));
        assertTrue((Boolean) method.invoke(null, realWithContextWindow));
    }

    @Test
    void modelCanCallConfiguredMcpToolThroughToolRegistry() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.MOCK,
                "mock-model",
                "mock://local",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "test",
                Map.of("Fake Server", fakeMcpConfig("happy"))
        );
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("home"),
                workspace,
                "session-1",
                config,
                event -> {
                    boolean hasMcpTool = event.stream()
                            .filter(SystemMessage.class::isInstance)
                            .map(SystemMessage.class::cast)
                            .map(SystemMessage::content)
                            .anyMatch(prompt -> prompt.contains("mcp__fake_server__echo_tool"));
                    if (!hasMcpTool) {
                        return new minicode.core.step.AssistantStep("missing mcp tool",
                                minicode.core.step.AssistantKind.FINAL);
                    }
                    boolean sawToolResult = event.stream()
                            .filter(ToolResultMessage.class::isInstance)
                            .map(ToolResultMessage.class::cast)
                            .anyMatch(message -> message.toolName().equals("mcp__fake_server__echo_tool")
                                    && message.content().contains("echo: hello"));
                    if (sawToolResult) {
                        return new minicode.core.step.AssistantStep("mcp complete",
                                minicode.core.step.AssistantKind.FINAL);
                    }
                    return new minicode.core.step.ToolCallsStep(List.of(new ToolCall(
                            "tool-use-1",
                            "mcp__fake_server__echo_tool",
                            JsonNodeFactory.instance.objectNode().put("value", "hello")
                    )), Optional.empty(), minicode.core.step.ContentKind.UNSPECIFIED,
                            List.of(), Optional.empty(), Optional.empty());
                },
                event -> {
                },
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE)
        );
        try {
            AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("call mcp")), 4));

            assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
            assertEquals("mcp complete", ((AssistantMessage) result.messages().getLast()).content());
            ToolResultMessage toolResult = result.messages().stream()
                    .filter(ToolResultMessage.class::isInstance)
                    .map(ToolResultMessage.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("mcp__fake_server__echo_tool", toolResult.toolName());
            assertTrue(toolResult.content().contains("echo: hello"));
        } finally {
            services.close();
        }
    }

    @Test
    void assemblyAllowsReasonableLargeToolResultBelowRaisedThreshold() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new HugeOutputTool("x".repeat(100_000)));
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        MockModelAdapter model = MockModelAdapter.toolThenFinal(
                new ToolCall("tool-use-1", "huge_output", JsonNodeFactory.instance.objectNode()),
                "done"
        );
        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(home.resolve("tool-results")),
                200_000,
                400_000,
                20_000
        );
        ApplicationServices services = servicesWithSingleTool(registry, contextManager, model, home, cwd);

        AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("run")), 3));

        ToolResultMessage toolResult = firstToolResult(result);
        assertFalse(toolResult.content().startsWith("<persisted-output "));
        assertEquals(100_000, toolResult.content().length());
    }

    @Test
    void largeToolResultAboveAssemblyThresholdGoesThroughContextReplacement() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new HugeOutputTool("x".repeat(210_000)));
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace").toAbsolutePath().normalize();
        MockModelAdapter model = MockModelAdapter.toolThenFinal(
                new ToolCall("tool-use-1", "huge_output", JsonNodeFactory.instance.objectNode()),
                "done"
        );
        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(home.resolve("tool-results")),
                200_000,
                400_000,
                20_000
        );
        ApplicationServices services = servicesWithSingleTool(registry, contextManager, model, home, cwd);

        AgentTurnResult result = services.runTurn(services.turnRequest(List.of(new UserMessage("run")), 3));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        ToolResultMessage toolResult = firstToolResult(result);
        assertTrue(toolResult.content().startsWith("<persisted-output "));
        assertTrue(toolResult.content().contains("ORIGINAL_CHARS: 210000"));
    }

    @Test
    void cancelledTurnStillEndsPermissionTurnLifecycle() {
        TrackingPermissionService permissionService = new TrackingPermissionService();
        ToolRegistry registry = new ToolRegistry();
        ContextManager contextManager = new ContextManager(new ToolResultStorage(tempDir.resolve("tool-results")),
                20_000, 2_000);
        SessionStore sessionStore = new SessionStore(tempDir.resolve("sessions"));
        MockModelAdapter modelAdapter = new MockModelAdapter("done");
        ApplicationServices services = new ApplicationServices(
                registry,
                permissionService,
                contextManager,
                sessionStore,
                new SessionPersistenceRunner(sessionStore, new SessionEventFactory("session-1", tempDir.toString())),
                new AgentLoop(modelAdapter, event -> {
                }, registry, contextManager),
                modelAdapter,
                new CompactService(),
                new SystemPromptBuilder(),
                new WorkspacePathResolver(),
                new SkillRegistry(List.of()),
                McpRuntime.empty(),
                PermissionStore.none(),
                tempDir.resolve("permissions.json"),
                tempDir,
                tempDir,
                "session-1"
        );
        AgentTurnRequest request = new AgentTurnRequest(
                "turn-cancelled",
                tempDir,
                "session-1",
                List.of(new UserMessage("hello")),
                3,
                java.util.Optional.empty(),
                minicode.core.turn.CancellationToken.cancelled(minicode.core.turn.CancellationSource.USER, "stop")
        );

        AgentTurnResult result = services.runTurn(request);

        assertEquals(AgentTurnStopReason.CANCELLED, result.stopReason());
        assertEquals(List.of("turn-cancelled"), permissionService.begun);
        assertEquals(List.of("turn-cancelled"), permissionService.ended);
    }

    @Test
    void coreDoesNotReferenceTuiLayer() throws Exception {
        Path coreRoot = Path.of("src/main/java/minicode/core");

        boolean referencesTui;
        try (var paths = Files.walk(coreRoot)) {
            referencesTui = paths.filter(Files::isRegularFile)
                    .anyMatch(path -> {
                        try {
                            return Files.readString(path).contains("minicode.tui");
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }

        assertFalse(referencesTui);
    }

    @Test
    void phaseFourAStillDoesNotIntroduceOpenAiOrSkillService() throws Exception {
        Path mainRoot = Path.of("src/main/java");
        List<String> forbidden = List.of("OpenAI", "SkillService");

        boolean foundForbiddenReference;
        try (var paths = Files.walk(mainRoot)) {
            foundForbiddenReference = paths.filter(Files::isRegularFile)
                    .anyMatch(path -> {
                        try {
                            String text = Files.readString(path);
                            return forbidden.stream().anyMatch(text::contains);
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }

        assertFalse(foundForbiddenReference);
    }

    private static final class TrackingPermissionService implements PermissionService {
        private final java.util.ArrayList<String> begun = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> ended = new java.util.ArrayList<>();

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void beginTurn(String turnId) {
            begun.add(turnId);
        }

        @Override
        public void endTurn(String turnId) {
            ended.add(turnId);
        }
    }

    private static final class AutoCompactRecordingModel implements minicode.core.loop.ModelAdapter {
        @Override
        public minicode.core.step.AgentStep next(List<ChatMessage> messages) {
            boolean summaryRequest = messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .map(UserMessage::content)
                    .anyMatch(content -> content.contains("Summarize the following MiniCode"));
            if (summaryRequest) {
                return new minicode.core.step.AssistantStep("auto summary", minicode.core.step.AssistantKind.FINAL);
            }
            return new minicode.core.step.AssistantStep("done", minicode.core.step.AssistantKind.FINAL);
        }
    }

    private ApplicationServices servicesWithSingleTool(ToolRegistry registry, ContextManager contextManager,
                                                       MockModelAdapter model, Path home, Path cwd) {
        SessionStore sessionStore = new SessionStore(home.resolve("sessions"));
        return new ApplicationServices(
                registry,
                new TrackingPermissionService(),
                contextManager,
                sessionStore,
                new SessionPersistenceRunner(sessionStore, new SessionEventFactory("session-1", cwd.toString())),
                new AgentLoop(model, event -> {
                }, registry, contextManager),
                model,
                new CompactService(),
                new SystemPromptBuilder(),
                new WorkspacePathResolver(),
                new SkillRegistry(List.of()),
                McpRuntime.empty(),
                PermissionStore.none(),
                home.resolve("permissions.json"),
                home,
                cwd,
                "session-1"
        );
    }

    private static ToolResultMessage firstToolResult(AgentTurnResult result) {
        return result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static final class HugeOutputTool implements Tool {
        private final String output;
        private final com.fasterxml.jackson.databind.node.ObjectNode schema =
                JsonNodeFactory.instance.objectNode().put("type", "object");

        private HugeOutputTool(String output) {
            this.output = output;
        }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "huge_output",
                    "Huge output test tool",
                    schema,
                    ToolOrigin.BUILTIN,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE
            );
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return schema;
        }

        @Override
        public ValidationResult validateInput(com.fasterxml.jackson.databind.JsonNode input) {
            return ValidationResult.valid(input);
        }

        @Override
        public ToolResult run(com.fasterxml.jackson.databind.JsonNode normalizedInput, ToolContext toolContext) {
            return ToolResult.ok(output);
        }
    }

    private static minicode.mcp.McpServerConfig fakeMcpConfig(String mode) {
        return new minicode.mcp.McpServerConfig(
                System.getProperty("java.home") + "\\bin\\java.exe",
                List.of("-cp", System.getProperty("java.class.path"), minicode.mcp.FakeMcpStdioServer.class.getName(), mode),
                null,
                Map.of(),
                true,
                java.time.Duration.ofMillis(800),
                java.time.Duration.ofMillis(800)
        );
    }
}
