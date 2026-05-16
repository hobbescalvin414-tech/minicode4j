package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.permissions.model.CommandClassification;
import minicode.permissions.model.CommandSignature;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDeniedException;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.permissions.model.PathIntent;
import minicode.tools.validation.ToolInputValidation;
import minicode.workspace.WorkspacePathException;
import minicode.workspace.WorkspacePathRequest;
import minicode.workspace.WorkspacePathResolver;
import minicode.workspace.WorkspacePathResult;
import minicode.workspace.WorkspaceBoundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RunCommandTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "run_command",
            "Run a workspace command. Explicit argv is recommended; single-string commands like \"git status\" are normalized when args is omitted. Shell snippets are rejected instead of being silently executed.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.COMMAND),
            ToolStatus.AVAILABLE
    );

    private final PermissionService permissionService;
    private final WorkspacePathResolver workspacePathResolver;
    private final CommandClassifier commandClassifier;
    private final CommandPolicy commandPolicy;
    private final CommandTimeoutPolicy timeoutPolicy;

    public RunCommandTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()));
    }

    public RunCommandTool(PermissionService permissionService) {
        this(permissionService, new CommandTimeoutPolicy(), new WorkspacePathResolver(),
                new CommandClassifier(), new CommandPolicy());
    }

    public RunCommandTool(PermissionService permissionService, Duration timeout) {
        this(permissionService, new CommandTimeoutPolicy(timeout, Duration.ofSeconds(CommandTimeoutPolicy.MAX_TIMEOUT_SECONDS)),
                new WorkspacePathResolver(), new CommandClassifier(), new CommandPolicy());
    }

    public RunCommandTool(PermissionService permissionService, Duration timeout,
                          WorkspacePathResolver workspacePathResolver) {
        this(permissionService, new CommandTimeoutPolicy(timeout, Duration.ofSeconds(CommandTimeoutPolicy.MAX_TIMEOUT_SECONDS)),
                workspacePathResolver, new CommandClassifier(), new CommandPolicy());
    }

    RunCommandTool(PermissionService permissionService,
                   CommandTimeoutPolicy timeoutPolicy,
                   WorkspacePathResolver workspacePathResolver,
                   CommandClassifier commandClassifier,
                   CommandPolicy commandPolicy) {
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
        this.workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
        this.commandClassifier = Objects.requireNonNull(commandClassifier, "commandClassifier");
        this.commandPolicy = Objects.requireNonNull(commandPolicy, "commandPolicy");
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        Optional<List<String>> singleStringCommand = normalizeSingleStringCommand(input);
        ValidationResult validation = ToolInputValidation.object(input)
                .requiredString("command")
                .optionalStringArray("args", true)
                .cwdField("cwd", false)
                .optionalInteger("timeoutSeconds", 1, timeoutPolicy.maxTimeoutSeconds())
                .optionalBoolean("background")
                .build();
        if (!validation.valid() || singleStringCommand.isEmpty()) {
            return validation;
        }
        ObjectNode normalized = (ObjectNode) validation.normalizedInput().orElseThrow();
        List<String> parts = singleStringCommand.orElseThrow();
        normalized.put("command", parts.getFirst());
        ArrayNode args = normalized.putArray("args");
        for (int index = 1; index < parts.size(); index++) {
            args.add(parts.get(index));
        }
        return ValidationResult.valid(normalized);
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String command = normalizedInput.get("command").asText();
        List<String> args = argsFrom(normalizedInput.get("args"));
        Duration commandTimeout = timeoutPolicy.timeoutFor(
                normalizedInput.has("timeoutSeconds") ? normalizedInput.get("timeoutSeconds").asInt() : null
        );
        if (normalizedInput.has("background") && normalizedInput.get("background").asBoolean()) {
            return ToolResult.error("background=true is not supported yet in Java run_command. "
                    + "No task was started. Future background task results will include task id, command, cwd, status, pid, startedAt, endedAt, and outputRef. "
                    + "Run a foreground command instead or ask the user before starting a long-lived process.");
        }

        try {
            WorkspacePathResult commandCwd = resolveCwd(toolContext, normalizedInput.get("cwd"));
            CommandClassificationResult classification = commandClassifier.classify(command, args);
            PermissionContext permissionContext = new PermissionContext(
                    toolContext.sessionId(),
                    toolContext.turnId(),
                    toolContext.toolUseId()
            );
            if (commandCwd.resolvedPath().boundary() == WorkspaceBoundary.OUTSIDE_CWD) {
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
                permissionService.ensurePath(
                        commandCwd.resolvedPath().normalizedPath(),
                        PathIntent.COMMAND_CWD,
                        permissionContext
                );
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            }
            if (commandPolicy.requiresCommandPermission(classification, commandCwd.resolvedPath().boundary())) {
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
                permissionService.ensureCommand(
                        new CommandSignature(command, args),
                        classification.classification(),
                        permissionContext
                );
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            }
            if (classification.shellSnippet() && shellSnippetPolicyRejects(command, args)) {
                return ToolResult.error("Shell snippets are not supported by this Java run_command implementation. "
                        + "Use explicit argv for simple commands, for example command=\"git\", args=[\"status\"]. "
                        + "Shell wrappers such as sh/bash/cmd/powershell are treated as sensitive and require permission.");
            }
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            CommandOutput output = execute(command, args, commandCwd.resolvedPath().normalizedPath(), commandTimeout,
                    toolContext);
            String content = formatResult(command, args, commandCwd.resolvedPath().normalizedPath(), output);
            return new ToolResult(content, output.exitCode() != 0, false, java.util.Optional.empty());
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to run command: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Command interrupted");
        } catch (ExecutionException exception) {
            return ToolResult.error("Failed to capture command output: " + executionFailureMessage(exception));
        } catch (TimeoutException exception) {
            return ToolResult.error("Command timed out after " + commandTimeout.toSeconds() + " seconds");
        }
    }

    private CommandOutput execute(String command, List<String> args, Path cwd, Duration commandTimeout,
                                  ToolContext toolContext)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(args);
        Process process = new ProcessBuilder(commandLine)
                .directory(cwd.toFile())
                .start();

        CompletableFuture<String> stdout = readStream(process.getInputStream());
        CompletableFuture<String> stderr = readStream(process.getErrorStream());
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(commandTimeout.toMillis());
        while (process.isAlive()) {
            try {
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            } catch (CancellationRequestedException exception) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                throw exception;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                throw new TimeoutException();
            }
            long waitMillis = Math.min(100, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            process.waitFor(Math.max(1, waitMillis), TimeUnit.MILLISECONDS);
        }

        return new CommandOutput(process.exitValue(), stdout.get(1, TimeUnit.SECONDS), stderr.get(1, TimeUnit.SECONDS));
    }

    private static CompletableFuture<String> readStream(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private WorkspacePathResult resolveCwd(ToolContext toolContext, JsonNode cwdNode) {
        String cwd = ".";
        if (cwdNode == null || cwdNode.isNull()) {
            cwd = ".";
        } else {
            cwd = cwdNode.asText();
        }
        return workspacePathResolver.resolve(new WorkspacePathRequest(
                toolContext.cwd(),
                cwd,
                PathIntent.COMMAND_CWD,
                true,
                true
        ));
    }

    private static List<String> argsFrom(JsonNode argsNode) {
        List<String> args = new ArrayList<>();
        argsNode.forEach(node -> args.add(node.asText()));
        return List.copyOf(args);
    }

    private Optional<List<String>> normalizeSingleStringCommand(JsonNode input) {
        if (input == null || !input.isObject()) {
            return Optional.empty();
        }
        JsonNode commandNode = input.get("command");
        if (commandNode == null || !commandNode.isTextual()) {
            return Optional.empty();
        }
        JsonNode argsNode = input.get("args");
        if (argsNode != null && argsNode.isArray() && !argsNode.isEmpty()) {
            return Optional.empty();
        }
        String commandLine = commandNode.asText().trim();
        if (commandLine.isBlank() || (!commandLine.contains(" ") && !commandLine.contains("\t"))) {
            return Optional.empty();
        }
        if (new ShellSnippetPolicy().looksLikeShellSnippet(commandLine, List.of())) {
            return Optional.empty();
        }
        List<String> parts = splitCommandLine(commandLine);
        return parts.size() > 1 ? Optional.of(parts) : Optional.empty();
    }

    private static List<String> splitCommandLine(String commandLine) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character quote = null;
        boolean escaping = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char ch = commandLine.charAt(index);
            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                continue;
            }
            if (quote != null) {
                if (ch == quote) {
                    quote = null;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (escaping) {
            current.append('\\');
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return List.copyOf(parts);
    }

    private static boolean shellSnippetPolicyRejects(String command, List<String> args) {
        return new ShellSnippetPolicy().looksLikeShellSnippet(command, args)
                && !isShellWrapper(command);
    }

    private static boolean isShellWrapper(String command) {
        String executable;
        try {
            Path fileName = Path.of(command).getFileName();
            executable = fileName == null ? command : fileName.toString();
        } catch (RuntimeException ignored) {
            executable = command;
        }
        executable = executable.toLowerCase(Locale.ROOT);
        return executable.equals("sh")
                || executable.equals("bash")
                || executable.equals("cmd")
                || executable.equals("cmd.exe")
                || executable.equals("powershell")
                || executable.equals("powershell.exe")
                || executable.equals("pwsh")
                || executable.equals("pwsh.exe");
    }

    private static String formatResult(String command, List<String> args, Path cwd, CommandOutput output) {
        return String.join("\n",
                "COMMAND: " + commandLine(command, args),
                "CWD: " + cwd,
                "EXIT_CODE: " + output.exitCode(),
                "STDOUT:",
                trimTrailingNewline(output.stdout()),
                "STDERR:",
                trimTrailingNewline(output.stderr())
        );
    }

    private static String commandLine(String command, List<String> args) {
        if (args.isEmpty()) {
            return command;
        }
        return command + " " + String.join(" ", args);
    }

    private static String trimTrailingNewline(String value) {
        return value.replaceAll("\\R+$", "");
    }

    private static String executionFailureMessage(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            String message = exception.getMessage();
            return message == null || message.isBlank() ? "unknown error" : message;
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode command = properties.putObject("command");
        command.put("type", "string");
        command.put("description", "Executable to run, or a simple single-string command when args is omitted. Prefer explicit argv, e.g. command=\"git\", args=[\"status\"]. Shell snippets and pipelines are rejected; shell wrappers are sensitive.");

        ObjectNode args = properties.putObject("args");
        args.put("type", "array");
        args.putObject("items").put("type", "string");
        args.put("description", "Arguments passed directly to ProcessBuilder. When non-empty, command is treated as the exact executable and is not split.");

        ObjectNode cwd = properties.putObject("cwd");
        cwd.put("type", "string");
        cwd.put("description", "Working directory. Relative paths are resolved from ToolContext.cwd.");

        ObjectNode timeoutSeconds = properties.putObject("timeoutSeconds");
        timeoutSeconds.put("type", "integer");
        timeoutSeconds.put("minimum", 1);
        timeoutSeconds.put("maximum", CommandTimeoutPolicy.MAX_TIMEOUT_SECONDS);
        timeoutSeconds.put("description", "Maximum seconds to wait for the command before killing it.");

        ObjectNode background = properties.putObject("background");
        background.put("type", "boolean");
        background.put("description", "Reserved for future background tasks. In Phase 2 Java, background=true returns a clear unsupported tool_result and starts no process.");

        ArrayNode required = schema.putArray("required");
        required.add("command");

        return schema;
    }

    private record CommandOutput(int exitCode, String stdout, String stderr) {
    }
}
