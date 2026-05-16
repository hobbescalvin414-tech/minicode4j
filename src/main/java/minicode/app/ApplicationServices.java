package minicode.app;

import minicode.context.manager.ContextManager;
import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.CompactRequest;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactPolicy;
import minicode.context.compact.CompactService;
import minicode.context.compact.CompactStatus;
import minicode.context.compact.CompactTrigger;
import minicode.context.compact.ManualCompactResult;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.loop.ModelAdapter;
import minicode.model.MockModelAdapter;
import minicode.model.ModelContextProfile;
import minicode.model.ModelMetadata;
import minicode.model.ModelMetadataResolver;
import minicode.model.anthropic.AnthropicModelAdapter;
import minicode.model.anthropic.AnthropicModelsApiClient;
import minicode.model.anthropic.HttpAnthropicTransport;
import minicode.mcp.McpRuntime;
import minicode.mcp.McpServerSummary;
import minicode.mcp.McpToolHydrator;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.permissions.store.JsonPermissionStore;
import minicode.permissions.store.PermissionStore;
import minicode.prompt.SystemPromptBuilder;
import minicode.session.factory.SessionEventFactory;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.store.SessionStore;
import minicode.skills.SkillDiscovery;
import minicode.skills.SkillRegistry;
import minicode.tools.builtin.AskUserTool;
import minicode.tools.builtin.EditFileTool;
import minicode.tools.builtin.GrepFilesTool;
import minicode.tools.builtin.ListFilesTool;
import minicode.tools.builtin.LoadSkillTool;
import minicode.tools.builtin.ModifyFileTool;
import minicode.tools.builtin.PatchFileTool;
import minicode.tools.builtin.ReadFilePathAccess;
import minicode.tools.builtin.ReadFileTool;
import minicode.tools.builtin.RunCommandTool;
import minicode.tools.builtin.WriteFileTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResultStorage;
import minicode.workspace.WorkspacePathResolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;

public record ApplicationServices(ToolRegistry toolRegistry,
                                  PermissionService permissionService,
                                  ContextManager contextManager,
                                  SessionStore sessionStore,
                                  SessionPersistenceRunner sessionPersistenceRunner,
                                  AgentLoop agentLoop,
                                  ModelAdapter modelAdapter,
                                  CompactService compactService,
                                  SystemPromptBuilder systemPromptBuilder,
                                  WorkspacePathResolver workspacePathResolver,
                                  SkillRegistry skillRegistry,
                                  McpRuntime mcpRuntime,
                                  PermissionStore permissionStore,
                                  Path permissionStorePath,
                                  Path home,
                                  Path cwd,
                                  String sessionId,
                                  Optional<RuntimeConfig> runtimeConfig) {
    private static final Duration MODEL_METADATA_TIMEOUT = Duration.ofSeconds(3);
    private static final int LARGE_TOOL_RESULT_THRESHOLD_CHARS = 200_000;
    private static final int TOOL_RESULT_BATCH_BUDGET_CHARS = 400_000;
    private static final int TOOL_RESULT_PREVIEW_CHARS = 20_000;

    public ApplicationServices(ToolRegistry toolRegistry,
                               PermissionService permissionService,
                               ContextManager contextManager,
                               SessionStore sessionStore,
                               SessionPersistenceRunner sessionPersistenceRunner,
                               AgentLoop agentLoop,
                               ModelAdapter modelAdapter,
                               CompactService compactService,
                               SystemPromptBuilder systemPromptBuilder,
                               WorkspacePathResolver workspacePathResolver,
                               SkillRegistry skillRegistry,
                               McpRuntime mcpRuntime,
                               PermissionStore permissionStore,
                               Path permissionStorePath,
                               Path home,
                               Path cwd,
                               String sessionId) {
        this(toolRegistry, permissionService, contextManager, sessionStore, sessionPersistenceRunner, agentLoop,
                modelAdapter, compactService, systemPromptBuilder, workspacePathResolver, skillRegistry, mcpRuntime,
                permissionStore, permissionStorePath, home, cwd, sessionId, Optional.empty());
    }

    public ApplicationServices {
        toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        permissionService = Objects.requireNonNull(permissionService, "permissionService");
        contextManager = Objects.requireNonNull(contextManager, "contextManager");
        sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        sessionPersistenceRunner = Objects.requireNonNull(sessionPersistenceRunner, "sessionPersistenceRunner");
        agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        compactService = Objects.requireNonNull(compactService, "compactService");
        systemPromptBuilder = Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder");
        workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
        skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
        mcpRuntime = Objects.requireNonNull(mcpRuntime, "mcpRuntime");
        permissionStore = Objects.requireNonNull(permissionStore, "permissionStore");
        permissionStorePath = Objects.requireNonNull(permissionStorePath, "permissionStorePath");
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionService permissionService = new PromptingPermissionService(permissionPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);

        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );
        CompactService compactService = new CompactService();
        AgentLoop agentLoop = new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(128_000, 8_000)),
                new AutoCompactController(compactService, AutoCompactPolicy.defaults()));
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, McpRuntime.empty(), permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, Optional.empty());
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        return createWithModelFactory(home, cwd, sessionId, eventSink, permissionPromptHandler, (registry, metadata) ->
                runtimeConfig.provider() == ProviderKind.MOCK
                        ? new MockModelAdapter("mock final")
                        : new AnthropicModelAdapter(runtimeConfig, registry,
                                new HttpAnthropicTransport(java.net.http.HttpClient.newHttpClient(),
                                        runtimeConfig.providerTimeout()),
                                Optional.of(resolveModelContextProfile(runtimeConfig, metadata).resolvedMaxOutputTokens())),
                Optional.of(runtimeConfig));
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        return createWithModelFactory(home, cwd, sessionId, eventSink, permissionPromptHandler,
                (ignored, metadata) -> modelAdapter, Optional.of(runtimeConfig));
    }

    private static ApplicationServices createWithModelFactory(Path home, Path cwd, String sessionId,
                                                              AgentEventSink eventSink,
                                                              PermissionPromptHandler permissionPromptHandler,
                                                              java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> modelFactory,
                                                              Optional<RuntimeConfig> runtimeConfig) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionService permissionService = new PromptingPermissionService(permissionPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);
        McpRuntime mcpRuntime = runtimeConfig
                .map(config -> McpToolHydrator.hydrate(config.mcpServers(), permissionService, actualCwd))
                .orElseGet(McpRuntime::empty);
        mcpRuntime.tools().forEach(registry::register);
        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        Optional<ModelMetadata> modelMetadata = runtimeConfig.flatMap(ApplicationServices::fetchModelMetadata);
        ModelAdapter modelAdapter = Objects.requireNonNull(modelFactory.apply(registry, modelMetadata), "modelAdapter");
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );
        CompactService compactService = new CompactService();
        AgentLoop agentLoop = runtimeConfig
                .map(config -> new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                        new ContextStatsCalculator(new TokenAccountingService(), modelContextWindow(config, modelMetadata)),
                        new AutoCompactController(compactService, AutoCompactPolicy.defaults())))
                .orElseGet(() -> new AgentLoop(modelAdapter, eventSink, registry, contextManager));
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, mcpRuntime, permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, runtimeConfig);
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig) {
        return modelContextWindow(runtimeConfig, Optional.empty());
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig, Optional<ModelMetadata> metadata) {
        ModelContextProfile profile = resolveModelContextProfile(runtimeConfig, metadata);
        return new ModelContextWindow(profile.contextWindow(), profile.outputReserve());
    }

    private static ModelContextProfile resolveModelContextProfile(RuntimeConfig runtimeConfig,
                                                                  Optional<ModelMetadata> metadata) {
        return new ModelMetadataResolver().resolve(
                runtimeConfig.model(),
                runtimeConfig.contextWindow(),
                runtimeConfig.maxOutputTokens(),
                metadata
        );
    }

    private static Optional<ModelMetadata> fetchModelMetadata(RuntimeConfig runtimeConfig) {
        if (!shouldFetchModelMetadata(runtimeConfig)) {
            return Optional.empty();
        }
        return metadataClient(runtimeConfig).fetch(runtimeConfig.model());
    }

    private static boolean shouldFetchModelMetadata(RuntimeConfig runtimeConfig) {
        return runtimeConfig.provider() != ProviderKind.MOCK;
    }

    private static AnthropicModelsApiClient metadataClient(RuntimeConfig runtimeConfig) {
        HttpAnthropicTransport transport = new HttpAnthropicTransport(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(MODEL_METADATA_TIMEOUT)
                        .build(),
                MODEL_METADATA_TIMEOUT
        );
        return new AnthropicModelsApiClient(runtimeConfig, transport);
    }

    private static ToolRegistry createBuiltInToolRegistry(PermissionService permissionService,
                                                         WorkspacePathResolver workspacePathResolver,
                                                         SkillRegistry skillRegistry) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AskUserTool());
        registry.register(new LoadSkillTool(skillRegistry));
        registry.register(new ReadFileTool(ReadFilePathAccess.fromPermissionService(permissionService),
                workspacePathResolver));
        registry.register(new RunCommandTool(permissionService, java.time.Duration.ofSeconds(5),
                workspacePathResolver));
        registry.register(new ListFilesTool(permissionService, workspacePathResolver));
        registry.register(new GrepFilesTool(permissionService, workspacePathResolver));
        registry.register(new WriteFileTool(permissionService, workspacePathResolver));
        registry.register(new EditFileTool(permissionService, workspacePathResolver));
        registry.register(new PatchFileTool(permissionService, workspacePathResolver));
        registry.register(new ModifyFileTool(permissionService, workspacePathResolver));
        return registry;
    }

    public AgentTurnRequest turnRequest(List<ChatMessage> messages, int maxSteps) {
        return new AgentTurnRequest(
                UUID.randomUUID().toString(),
                cwd,
                sessionId,
                withFreshSystemPrompt(messages),
                maxSteps,
                Optional.empty()
        );
    }

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        AgentTurnRequest actualRequest = Objects.requireNonNull(request, "request");
        actualRequest = new AgentTurnRequest(
                actualRequest.turnId(),
                actualRequest.cwd(),
                actualRequest.sessionId(),
                withFreshSystemPrompt(actualRequest.messages()),
                actualRequest.maxSteps(),
                actualRequest.modelName(),
                actualRequest.cancellationToken()
        );
        permissionService.beginTurn(actualRequest.turnId());
        try {
            return agentLoop.runTurn(actualRequest);
        } finally {
            permissionService.endTurn(actualRequest.turnId());
        }
    }

    public List<ChatMessage> sessionMessages() {
        return sessionStore.loadMessagesSinceLatestCompactBoundary(sessionId, cwd.toString());
    }

    public ManualCompactResult manualCompact() {
        ManualCompactResult result = compactService.compact(new CompactRequest(
                withFreshSystemPrompt(sessionMessages()),
                modelAdapter,
                CompactTrigger.MANUAL
        ));
        if (result.status() == CompactStatus.COMPACTED) {
            List<PersistenceAction> actions = new ArrayList<>();
            actions.add(new PersistenceAction.AppendCompactBoundaryAction(
                    result.boundary().orElseThrow().summaryMessage(),
                    result.boundary().orElseThrow().metadata()
            ));
            List<ChatMessage> retainedMessages = retainedMessagesAfterCompactBoundary(result);
            if (!retainedMessages.isEmpty()) {
                actions.add(new PersistenceAction.AppendMessagesAction(retainedMessages));
            }
            sessionPersistenceRunner.apply(new TurnPersistencePlan(actions));
        }
        return result;
    }

    public List<McpServerSummary> mcpServerSummaries() {
        return mcpRuntime.summaries();
    }

    public void close() {
        mcpRuntime.close();
    }

    private List<ChatMessage> retainedMessagesAfterCompactBoundary(ManualCompactResult result) {
        ChatMessage summary = result.boundary().orElseThrow().summaryMessage();
        boolean skippedSummary = false;
        List<ChatMessage> retained = new ArrayList<>();
        for (ChatMessage message : result.messages()) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (!skippedSummary && message.equals(summary)) {
                skippedSummary = true;
                continue;
            }
            retained.add(message);
        }
        return List.copyOf(retained);
    }

    private List<ChatMessage> withFreshSystemPrompt(List<ChatMessage> messages) {
        List<ChatMessage> refreshed = new ArrayList<>();
        refreshed.add(new SystemMessage(systemPromptBuilder.build(
                new SystemPromptBuilder.Input(home, cwd, toolRegistry, skillRegistry.summaries(),
                        mcpRuntime.summaries())
        )));
        for (ChatMessage message : messages) {
            if (!(message instanceof SystemMessage)) {
                refreshed.add(message);
            }
        }
        return List.copyOf(refreshed);
    }
}
