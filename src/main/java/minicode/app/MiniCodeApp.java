package minicode.app;

import minicode.config.RuntimeConfig;
import minicode.config.RuntimeConfigException;
import minicode.config.RuntimeConfigLoader;
import minicode.app.ui.UiStdioMockBackend;
import minicode.app.ui.UiStdioRealBackend;
import minicode.app.ui.UiStdioRunTurnBackend;
import minicode.core.event.AgentEventSink;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.session.service.SessionService;
import minicode.session.store.SessionMetadata;
import minicode.session.store.SessionStore;
import minicode.tui.ConsolePermissionPromptHandler;
import minicode.tui.MiniTui;
import minicode.tui.MiniTuiEventSink;
import minicode.tui.RendererTuiBridge;
import minicode.tui.RendererTuiShell;
import minicode.tui.input.JLineTuiInput;
import minicode.tui.terminal.JLineTerminalScreen;
import minicode.tui.terminal.TerminalScreen;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;

public final class MiniCodeApp {
    private static final String VERSION = MiniCodeApp.class.getPackage().getImplementationVersion() == null
            ? "0.1.0-SNAPSHOT"
            : MiniCodeApp.class.getPackage().getImplementationVersion();

    private MiniCodeApp() {
    }

    public static void main(String[] args) {
        int exitCode = run(
                args,
                Path.of(System.getProperty("user.home"), ".minicode-java"),
                Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                System.in,
                System.out,
                System.err,
                System.getenv()
        );
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                          OutputStream error, Map<String, String> env) {
        return run(args, home, cwd, input, output, error, env, MiniCodeApp::launchSnakeGame);
    }

    public static int run(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                          OutputStream error, Map<String, String> env, Runnable snakeLauncher) {
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        AppArgs appArgs;
        Path actualCwd;
        try {
            appArgs = AppArgs.parse(args);
            actualCwd = resolveActualCwd(cwd, appArgs);
        } catch (RuntimeException exception) {
            err.println("Runtime error: " + safeMessage(exception, env));
            return 1;
        }
        if (appArgs.snake()) {
            PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
            out.println("Starting SnakeGame...");
            try {
                snakeLauncher.run();
                return 0;
            } catch (RuntimeException exception) {
                err.println("Runtime error: " + safeMessage(exception, env));
                return 1;
            }
        }
        if (appArgs.help()) {
            new PrintWriter(output, true, StandardCharsets.UTF_8).println(usage());
            return 0;
        }
        if (appArgs.version()) {
            new PrintWriter(output, true, StandardCharsets.UTF_8).println("minicode " + VERSION);
            return 0;
        }
        if (appArgs.uiStdioMock()) {
            new UiStdioMockBackend().run(actualCwd, output);
            return 0;
        }
        if (appArgs.uiStdioMockRun()) {
            new UiStdioRunTurnBackend().run(home, actualCwd, input, output,
                    effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()), java.util.Optional.empty()));
            return 0;
        }
        if (appArgs.uiStdioRun()) {
            RuntimeConfig runtimeConfig;
            try {
                runtimeConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, actualCwd, env));
            } catch (RuntimeConfigException exception) {
                err.println("Configuration error: " + exception.getMessage());
                err.println("Configure MINICODE_PROVIDER, ANTHROPIC_MODEL or MINICODE_MODEL, and ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY.");
                err.println("Mock mode is only used when MINICODE_PROVIDER=mock is explicitly set.");
                return 2;
            }
            UiStdioRealBackend.real(runtimeConfig).run(home, actualCwd, input, output,
                    effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()), java.util.Optional.of(runtimeConfig)));
            return 0;
        }
        if (appArgs.sessionCommand()) {
            return runWithServices(args, home, cwd, input, output, error,
                    (serviceHome, serviceCwd, sessionId, eventSink, permissionPromptHandler) -> {
                        throw new IllegalStateException("Session management command must not start application services.");
                    },
                    env);
        }
        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, actualCwd, env));
        } catch (RuntimeConfigException exception) {
            err.println("Configuration error: " + exception.getMessage());
            err.println("Configure MINICODE_PROVIDER, ANTHROPIC_MODEL or MINICODE_MODEL, and ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY.");
            err.println("Mock mode is only used when MINICODE_PROVIDER=mock is explicitly set.");
            return 2;
        }
        return runWithServices(args, home, cwd, input, output, error,
                (serviceHome, serviceCwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        serviceHome,
                        serviceCwd,
                        sessionId,
                        runtimeConfig,
                        eventSink,
                        permissionPromptHandler
                ),
                env);
    }

    public static int runWithServices(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                                      OutputStream error, ServicesFactory servicesFactory) {
        return runWithServices(args, home, cwd, input, output, error, servicesFactory, Map.of());
    }

    private static int runWithServices(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                                       OutputStream error, ServicesFactory servicesFactory, Map<String, String> env) {
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        try {
            runWithServicesUnchecked(args, home, cwd, input, output, error, servicesFactory);
            return 0;
        } catch (RuntimeException exception) {
            err.println("Runtime error: " + safeMessage(exception, env));
            return 1;
        }
    }

    private static void runWithServicesUnchecked(String[] args, Path home, Path cwd, InputStream input,
                                                 OutputStream output, OutputStream error, ServicesFactory servicesFactory) {
        PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        AppArgs appArgs = AppArgs.parse(args);
        Path actualCwd = resolveActualCwd(cwd, appArgs);
        Path actualHome = home.toAbsolutePath().normalize();
        SessionService sessionService = new SessionService(new SessionStore(actualHome.resolve("sessions")));
        if (appArgs.sessionCommand()) {
            handleSessionCommand(appArgs, sessionService, actualCwd.toString(), out);
            return;
        }
        String sessionId = appArgs.sessionId().orElseGet(() -> UUID.randomUUID().toString());
        if (appArgs.resumeSessionId() != null) {
            sessionService.requireResumable(actualCwd.toString(), appArgs.resumeSessionId());
            sessionId = appArgs.resumeSessionId();
        }
        if (appArgs.forkSessionId() != null) {
            sessionId = sessionService.fork(actualCwd.toString(), appArgs.forkSessionId());
            out.println("Forked session " + appArgs.forkSessionId() + " -> " + sessionId);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        Terminal terminal = createTerminal(input, output, err);
        RendererTuiBridge rendererBridge = terminal == null ? null : new RendererTuiBridge();
        PermissionPromptHandler permissionPromptHandler = rendererBridge == null
                ? new ConsolePermissionPromptHandler(reader, output)
                : rendererBridge;

        ApplicationServices services = servicesFactory.create(
                actualHome,
                actualCwd,
                sessionId,
                rendererBridge == null ? new MiniTuiEventSink(output, event -> {
                }) : rendererBridge,
                permissionPromptHandler
        );
        TerminalScreen terminalScreen = terminal == null ? null : new JLineTerminalScreen(terminal);
        try {
            if (terminal == null) {
                new MiniTui(services, reader, output,
                        effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()),
                                services.runtimeConfig())).runLoop();
            } else {
                new RendererTuiShell(services, new JLineTuiInput(terminal), terminalScreen,
                        effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()),
                                services.runtimeConfig()), rendererBridge).runLoop();
            }
        } finally {
            if (terminalScreen != null) {
                terminalScreen.close();
            }
            services.close();
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (java.io.IOException ignored) {
                    // Closing the terminal is best-effort during app shutdown.
                }
            }
        }
    }

    private static Terminal createTerminal(InputStream input, OutputStream output, PrintWriter err) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .streams(input, output)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            return terminal.getType() == null || "dumb".equalsIgnoreCase(terminal.getType()) ? null : terminal;
        } catch (RuntimeException | java.io.IOException exception) {
            err.println("TUI fallback: JLine terminal unavailable, using line mode. " + exception.getMessage());
            return null;
        }
    }

    private static Path resolveActualCwd(Path cwd, AppArgs appArgs) {
        Path actualCwd = appArgs.cwdOverride() != null
                ? appArgs.cwdOverride().toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();
        if (appArgs.cwdOverride() != null && (!Files.exists(actualCwd) || !Files.isDirectory(actualCwd))) {
            throw new IllegalArgumentException("--cwd must be an existing directory: " + actualCwd);
        }
        return actualCwd;
    }

    private static void handleSessionCommand(AppArgs args, SessionService sessionService, String cwd, PrintWriter out) {
        List<String> command = args.remaining();
        String subcommand = command.size() > 1 ? command.get(1) : "";
        switch (subcommand) {
            case "list" -> {
                List<SessionMetadata> sessions = sessionService.list(cwd);
                if (sessions.isEmpty()) {
                    out.println("No sessions for cwd: " + cwd);
                    return;
                }
                out.println(formatSessionListHeader());
                sessions.forEach(session -> out.println(formatSessionListRow(session)));
            }
            case "rename" -> {
                if (command.size() < 4) {
                    throw new IllegalArgumentException("Usage: session rename <id> <title>");
                }
                String sessionId = command.get(2);
                String title = String.join(" ", command.subList(3, command.size()));
                sessionService.rename(cwd, sessionId, title);
                out.println("Renamed session " + sessionId + " to " + title.trim());
            }
            default -> throw new IllegalArgumentException("Usage: session list | session rename <id> <title>");
        }
    }

    private static String usage() {
        return """
                Usage:
                  minicode
                  minicode --tty
                  minicode --cwd <path>
                  minicode --resume <id>
                  minicode --fork <id>
                  minicode session list
                  minicode session rename <id> <title>
                  minicode --max-steps <n>
                  minicode --ui-stdio-mock
                  minicode --ui-stdio-mock-run
                  minicode --ui-stdio-run
                  minicode --version
                  minicode --help

                Options:
                  --tty              Use the legacy Java TTY frontend when launched from packaged scripts.
                  --cwd <path>       Use an explicit workspace directory.
                  --resume <id>      Resume a session for the current workspace.
                  --fork <id>        Fork a session for the current workspace.
                  --max-steps <n>    Limit one agent turn to 1..100 model/tool steps.
                  --ui-stdio-mock    Print fixed experimental UI JSONL events and exit.
                  --ui-stdio-mock-run
                                      Run experimental UI JSONL bridge with mock provider.
                  --ui-stdio-run
                                      Run experimental UI JSONL bridge with real runtime config.
                  --version          Print version and exit.
                  --help             Print this help and exit.
                """;
    }

    private static String formatSessionListHeader() {
        return String.format(java.util.Locale.ROOT, "%-36s  %-40s  %-30s  %s",
                "SESSION ID", "TITLE", "UPDATED", "CWD");
    }

    private static String formatSessionListRow(SessionMetadata session) {
        return String.format(java.util.Locale.ROOT, "%-36s  %-40s  %-30s  %s",
                truncate(session.sessionId(), 36),
                truncate(session.title().orElse("(untitled)"), 40),
                truncate(session.updatedAt().toString(), 30),
                session.cwd());
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

    private static String safeMessage(RuntimeException exception, Map<String, String> env) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        for (String key : java.util.List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                message = message.replace(value, "<redacted>");
            }
        }
        return message;
    }

    static int effectiveMaxSteps(java.util.Optional<Integer> cliMaxSteps,
                                 java.util.Optional<RuntimeConfig> runtimeConfig) {
        return cliMaxSteps
                .or(() -> runtimeConfig.flatMap(RuntimeConfig::maxSteps))
                .orElse(MiniTui.DEFAULT_MAX_STEPS);
    }

    private static void launchSnakeGame() {
        Path jar = snakeJarPath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable, "-jar", jar.toString());
        processBuilder.inheritIO();
        try {
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("SnakeGame exited with code " + exitCode);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to start SnakeGame: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SnakeGame.", exception);
        }
    }

    private static Path snakeJarPath() {
        java.util.ArrayList<Path> candidates = new java.util.ArrayList<>();
        String override = System.getProperty("minicode.snake.jar");
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }
        codeSourcePath().ifPresent(codePath -> {
            Path parent = Files.isRegularFile(codePath) ? codePath.getParent() : codePath;
            if (parent != null) {
                candidates.add(parent.resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("..").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("..").resolve("..").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("dist").resolve("minicode").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
            }
        });
        candidates.add(Path.of("easter-eggs", "snake", "snake.jar"));
        candidates.add(Path.of("target", "dist", "minicode", "easter-eggs", "snake", "snake.jar"));

        for (Path candidate : candidates) {
            Path actual = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(actual)) {
                return actual;
            }
        }
        throw new IllegalStateException("SnakeGame jar not found. Expected easter-eggs/snake/snake.jar near MiniCode.");
    }

    private static java.util.Optional<Path> codeSourcePath() {
        try {
            return java.util.Optional.of(Path.of(MiniCodeApp.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @FunctionalInterface
    public interface ServicesFactory {
        ApplicationServices create(Path home, Path cwd, String sessionId, AgentEventSink eventSink,
                                   PermissionPromptHandler permissionPromptHandler);
    }

    private record AppArgs(String resumeSessionId, String forkSessionId, Path cwdOverride,
                           boolean help, boolean version, boolean snake, boolean uiStdioMock, boolean uiStdioMockRun,
                           boolean uiStdioRun,
                           Integer maxStepsOverride, List<String> remaining) {
        private static final int DEFAULT_MAX_STEPS = MiniTui.DEFAULT_MAX_STEPS;
        private static final int MAX_MAX_STEPS = 100;

        private static AppArgs parse(String[] args) {
            List<String> remaining = new java.util.ArrayList<>(Arrays.asList(args));
            boolean help = takeFlag(remaining, "--help") || takeFlag(remaining, "-h");
            boolean version = takeFlag(remaining, "--version");
            boolean snake = takeFlag(remaining, "--snake");
            boolean uiStdioMock = takeFlag(remaining, "--ui-stdio-mock");
            boolean uiStdioMockRun = takeFlag(remaining, "--ui-stdio-mock-run");
            boolean uiStdioRun = takeFlag(remaining, "--ui-stdio-run");
            String cwd = takeOption(remaining, "--cwd");
            String maxSteps = takeOption(remaining, "--max-steps");
            String resume = takeOption(remaining, "--resume");
            String fork = takeOption(remaining, "--fork");
            if (resume != null && fork != null) {
                throw new IllegalArgumentException("Use either --resume or --fork, not both.");
            }
            return new AppArgs(resume, fork, cwd == null ? null : Path.of(cwd), help, version, snake, uiStdioMock,
                    uiStdioMockRun, uiStdioRun,
                    maxSteps == null ? null : parseMaxSteps(maxSteps),
                    List.copyOf(remaining));
        }

        private boolean sessionCommand() {
            return !remaining.isEmpty() && "session".equals(remaining.getFirst());
        }

        private java.util.Optional<String> sessionId() {
            if (remaining.isEmpty()) {
                return java.util.Optional.empty();
            }
            String first = remaining.getFirst();
            if (first.startsWith("-")) {
                throw new IllegalArgumentException("Unknown argument: " + first);
            }
            return java.util.Optional.of(first);
        }

        private static String takeOption(List<String> args, String name) {
            int index = args.indexOf(name);
            if (index < 0) {
                return null;
            }
            if (index + 1 >= args.size()) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args.get(index + 1);
            args.remove(index + 1);
            args.remove(index);
            return value;
        }

        private static boolean takeFlag(List<String> args, String name) {
            boolean found = false;
            while (args.remove(name)) {
                found = true;
            }
            return found;
        }

        private static int parseMaxSteps(String value) {
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--max-steps must be between 1 and " + MAX_MAX_STEPS);
            }
            if (parsed < 1 || parsed > MAX_MAX_STEPS) {
                throw new IllegalArgumentException("--max-steps must be between 1 and " + MAX_MAX_STEPS);
            }
            return parsed;
        }
    }
}
