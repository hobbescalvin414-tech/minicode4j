package minicode.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;
import minicode.core.turn.CancellationRequestedException;
import minicode.permissions.model.*;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.builtin.RunCommandTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RunCommandToolTest {
    @TempDir
    Path tempDir;

    @Test
    void permissionDenialDoesNotExecuteCommandAndReturnsFeedback() {
        Path marker = tempDir.resolve("marker.txt");
        ToolRegistry registry = registry(new PromptingPermissionService(
                PermissionPromptHandler.deny(PermissionDecision.DENY_WITH_FEEDBACK, "Do not run this command")
        ));

        ToolResult result = registry.execute(call(javaInput(WriteMarker.class, marker.toString())), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("Do not run this command"));
        assertFalse(Files.exists(marker));
    }

    @Test
    void permissionAllowExecutesSafeCrossPlatformCommand() {
        ToolRegistry registry = registry(allowingPermissionService());

        ToolResult result = registry.execute(call(javaInput(PrintMessage.class)), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("EXIT_CODE: 0"));
        assertTrue(result.content().contains("STDOUT:"));
        assertTrue(result.content().contains("hello-from-helper"));
    }

    @Test
    void readonlyCommandInsideCwdExecutesWithoutCommandPrompt() {
        CapturingPermissionService permissionService = new CapturingPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", javaExecutable().toString());
        input.putArray("args").add("-version");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("EXIT_CODE: 0"));
        assertNull(permissionService.lastSignature);
    }

    @Test
    void dangerousCommandRequiresCommandPrompt() {
        CapturingPermissionService permissionService = new CapturingPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "rm");
        input.putArray("args").add("-rf").add("target");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNotNull(permissionService.lastSignature);
        assertEquals(CommandClassification.DANGEROUS, permissionService.lastClassification);
    }

    @Test
    void shellSnippetRequiresCommandPromptAndIsNotTreatedAsReadonlyArgv() {
        CapturingPermissionService permissionService = new CapturingPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "pwd");
        input.putArray("args").add("&&").add("rm").add("-rf").add("target");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNotNull(permissionService.lastSignature);
        assertEquals(CommandClassification.SENSITIVE, permissionService.lastClassification);
    }

    @Test
    void shellSnippetControlOperatorInArgsIsRejectedAfterPermissionInsteadOfExecutingAsArgv() throws Exception {
        Path marker = tempDir.resolve("shell-argv-marker.txt");
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = javaInput(WriteMarker.class, marker.toString());
        input.withArray("args").add("&&");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("Shell snippets are not supported"));
        assertFalse(Files.exists(marker));
    }

    @Test
    void catOrTypeWithPathArgumentRequiresCommandPermissionInsideCwd() {
        DenyingCommandPermissionService permissionService = new DenyingCommandPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", isWindows() ? "type" : "cat");
        input.putArray("args").add(tempDir.resolve("secret.txt").toString());

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNotNull(permissionService.lastSignature);
        assertNotEquals(CommandClassification.READONLY, permissionService.lastClassification);
    }

    @Test
    void lsOrDirWithOutsidePathArgumentRequiresCommandPermissionInsideCwd() {
        DenyingCommandPermissionService permissionService = new DenyingCommandPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", isWindows() ? "dir" : "ls");
        input.putArray("args").add(tempDir.getParent().toAbsolutePath().normalize().toString());

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNotNull(permissionService.lastSignature);
        assertNotEquals(CommandClassification.READONLY, permissionService.lastClassification);
    }

    @Test
    void unknownCommandRequiresCommandPermissionInsideCwd() {
        DenyingCommandPermissionService permissionService = new DenyingCommandPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "custom-tool");
        input.putArray("args").add("arg");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNotNull(permissionService.lastSignature);
        assertEquals(CommandClassification.UNKNOWN, permissionService.lastClassification);
    }

    @Test
    void developmentCommandInsideCwdDoesNotRequireCommandPermissionByDefault() {
        DenyingCommandPermissionService permissionService = new DenyingCommandPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "mvn");
        input.putArray("args").add("test");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNull(permissionService.lastSignature);
    }

    @Test
    void shellWrapperRequiresCommandPermissionInsideCwd() {
        DenyingCommandPermissionService permissionService = new DenyingCommandPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        if (isWindows()) {
            input.put("command", "cmd");
            input.putArray("args").add("/c").add("dir");
        } else {
            input.put("command", "sh");
            input.putArray("args").add("-c").add("ls");
        }

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertNotNull(permissionService.lastSignature);
        assertEquals(CommandClassification.SENSITIVE, permissionService.lastClassification);
    }

    @Test
    void nonZeroExitCodeReturnsErrorToolResult() {
        ToolRegistry registry = registry(allowingPermissionService());

        ToolResult result = registry.execute(call(javaInput(FailWithExit.class)), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("EXIT_CODE: 7"));
        assertTrue(result.content().contains("STDERR:"));
        assertTrue(result.content().contains("helper-failed"));
    }

    @Test
    void cwdInputIsResolvedRelativeToToolContextCwd() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = javaInput(PrintCwd.class);
        input.put("cwd", "workspace");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertFalse(result.error());
        String expectedCwd = workspace.toAbsolutePath().normalize().toString();
        assertTrue(result.content().contains("CWD: " + expectedCwd));
        assertTrue(result.content().contains(expectedCwd));
    }

    @Test
    void cwdOutsideToolContextCwdRequestsCommandCwdPathPermissionBeforeCommandPermission() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        CapturingPermissionService permissionService = new CapturingPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = javaInput(PrintCwd.class);
        input.put("cwd", outside.toString());

        ToolResult result = registry.execute(call(input), context(workspace));

        assertFalse(result.error());
        assertEquals(outside.toAbsolutePath().normalize(), permissionService.lastPath);
        assertEquals(PathIntent.COMMAND_CWD, permissionService.lastPathIntent);
        assertNotNull(permissionService.lastSignature);
    }

    @Test
    void cwdOutsideToolContextCwdDenialDoesNotExecuteCommandPermission() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        ToolRegistry registry = registry(new PermissionService() {
            @Override
            public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
                throw new PermissionDeniedException(
                        new PermissionRequest(
                                "request-1",
                                PermissionRequestKind.PATH,
                                new PermissionResource.PathResource(path, intent),
                                "cwd outside denied",
                                context.toolUseId()
                        ),
                        Optional.of("cwd outside denied")
                );
            }

            @Override
            public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                                 PermissionContext context) {
                fail("command permission should not be requested after cwd denial");
                return null;
            }
        });
        ObjectNode input = javaInput(PrintCwd.class);
        input.put("cwd", outside.toString());

        ToolResult result = registry.execute(call(input), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("cwd outside denied"));
    }

    @Test
    void missingCwdReturnsToolErrorBeforeCommandPermission() {
        CapturingPermissionService permissionService = new CapturingPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = javaInput(PrintMessage.class);
        input.put("cwd", "missing-workspace");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("Path does not exist"));
        assertNull(permissionService.lastSignature);
    }

    @Test
    void validationFailureReturnsErrorToolResult() {
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.putArray("args").add("ignored");

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("command"));
    }

    @Test
    void validationNormalizesCommandArgsAndCwdWithoutChangingRules() {
        RunCommandTool tool = new RunCommandTool(allowingPermissionService());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "  java  ");
        input.putArray("args").add("-version");
        input.put("cwd", "  .  ");
        input.put("timeoutSeconds", 2);

        ValidationResult validation = tool.validateInput(input);

        assertTrue(validation.valid());
        assertEquals("java", validation.normalizedInput().orElseThrow().get("command").asText());
        assertEquals("-version", validation.normalizedInput().orElseThrow().get("args").get(0).asText());
        assertEquals(".", validation.normalizedInput().orElseThrow().get("cwd").asText());
        assertEquals(2, validation.normalizedInput().orElseThrow().get("timeoutSeconds").asInt());
    }

    @Test
    void validationNormalizesSingleStringCommandWhenArgsAreOmitted() {
        RunCommandTool tool = new RunCommandTool(allowingPermissionService());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "git status");

        ValidationResult validation = tool.validateInput(input);

        assertTrue(validation.valid());
        ObjectNode normalized = (ObjectNode) validation.normalizedInput().orElseThrow();
        assertEquals("git", normalized.get("command").asText());
        assertEquals("status", normalized.get("args").get(0).asText());
    }

    @Test
    void explicitArgsArePreferredOverSingleStringParsing() {
        RunCommandTool tool = new RunCommandTool(allowingPermissionService());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "git status");
        input.putArray("args").add("--short");

        ValidationResult validation = tool.validateInput(input);

        assertTrue(validation.valid());
        ObjectNode normalized = (ObjectNode) validation.normalizedInput().orElseThrow();
        assertEquals("git status", normalized.get("command").asText());
        assertEquals("--short", normalized.get("args").get(0).asText());
    }

    @Test
    void timeoutSecondsAboveMaximumFailsValidation() {
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = javaInput(PrintMessage.class);
        input.put("timeoutSeconds", 61);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("timeoutSeconds"));
    }

    @Test
    void backgroundCommandReturnsExplicitUnsupportedToolResultAndStartsNothing() {
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = javaInput(PrintMessage.class);
        input.put("background", true);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("background=true is not supported"));
        assertTrue(result.content().contains("task id"));
        assertTrue(result.backgroundTask().isEmpty());
    }

    @Test
    void timeoutKillsProcessBeforeItCanWriteMarker() throws Exception {
        Path marker = tempDir.resolve("timeout-marker.txt");
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = javaInput(SleepThenWrite.class, marker.toString());
        input.put("timeoutSeconds", 1);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("timed out after 1 seconds"));
        Thread.sleep(2500);
        assertFalse(Files.exists(marker));
    }

    @Test
    void cancellationKillsRunningCommandBeforeItCanWriteMarker() throws Exception {
        Path marker = tempDir.resolve("cancel-marker.txt");
        CancellationToken token = CancellationToken.create();
        ToolRegistry registry = registry(allowingPermissionService());
        ObjectNode input = javaInput(SleepThenWrite.class, marker.toString());
        input.put("timeoutSeconds", 10);

        CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(
                () -> registry.execute(call(input), context(tempDir, token))
        );
        Thread.sleep(500);
        token.requestCancellation(CancellationSource.USER, "cancel command");

        ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));

        CancellationRequestedException cancellation = assertInstanceOf(
                CancellationRequestedException.class,
                exception.getCause()
        );
        assertTrue(cancellation.getMessage().contains("cancel"));
        assertFalse(cancellation.getMessage().contains("timed out"));
        Thread.sleep(2500);
        assertFalse(Files.exists(marker));
    }

    @Test
    void cancellationAfterCommandPermissionPreventsCommandExecution() {
        Path marker = tempDir.resolve("permission-cancel-marker.txt");
        CancellationToken token = CancellationToken.create();
        PermissionService permissionService = new PermissionService() {
            @Override
            public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
                return grant(new PermissionResource.PathResource(path, intent));
            }

            @Override
            public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                                 PermissionContext context) {
                token.requestCancellation(CancellationSource.USER, "cancel after permission");
                return grant(new PermissionResource.CommandResource(signature, classification));
            }
        };
        ToolRegistry registry = registry(permissionService);

        ExecutionException exception = assertThrows(ExecutionException.class, () ->
                CompletableFuture.supplyAsync(() ->
                        registry.execute(call(javaInput(WriteMarker.class, marker.toString())), context(tempDir, token))
                ).get(5, TimeUnit.SECONDS)
        );

        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
        assertFalse(Files.exists(marker));
    }

    @Test
    void commandPermissionReceivesCommandSignatureBeforeExecution() {
        CapturingPermissionService permissionService = new CapturingPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(javaInput(PrintMessage.class)), context(tempDir));

        assertFalse(result.error());
        assertEquals(javaExecutable().toString(), permissionService.lastSignature.executable());
        assertTrue(permissionService.lastSignature.arguments().contains(PrintMessage.class.getName()));
    }

    @Test
    void metadataAndSchemaStayConciseWithoutEnvironmentSpecificGuidance() {
        RunCommandTool tool = new RunCommandTool(allowingPermissionService());

        String metadata = tool.metadata().description();
        String schema = tool.inputSchema().toString();

        assertTrue(metadata.contains("Run a workspace command"));
        assertTrue(metadata.contains("Explicit argv is recommended"));
        assertTrue(schema.contains("Executable to run"));
        assertTrue(schema.contains("Arguments passed directly to ProcessBuilder"));
        assertFalse(metadata.contains("mvn.cmd"));
        assertFalse(metadata.contains("powershell -NoProfile -Command"));
        assertFalse(schema.contains("OutputEncoding"));
        assertFalse(schema.contains("avoid one-line PowerShell env var setup"));
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RunCommandTool(permissionService));
        return registry;
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "run_command", input);
    }

    private static ObjectNode javaInput(Class<?> mainClass, String... extraArgs) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", javaExecutable().toString());
        ArrayNode args = input.putArray("args");
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        args.add(mainClass.getName());
        for (String arg : extraArgs) {
            args.add(arg);
        }
        return input;
    }

    private static Path javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static ToolContext context(Path cwd) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }

    private static ToolContext context(Path cwd, CancellationToken token) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"), token);
    }

    private static PermissionService allowingPermissionService() {
        return new PromptingPermissionService(PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE));
    }

    private static PermissionGrant grant(PermissionResource resource) {
        return new PermissionGrant(PermissionKind.COMMAND, resource, PermissionGrantScope.ONCE,
                PermissionPersistence.MEMORY, Instant.now(), Optional.empty());
    }

    private static final class CapturingPermissionService implements PermissionService {
        private CommandSignature lastSignature;
        private CommandClassification lastClassification;
        private Path lastPath;
        private PathIntent lastPathIntent;

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            lastPath = path;
            lastPathIntent = intent;
            PermissionResource resource = new PermissionResource.PathResource(path, intent);
            return new PermissionGrant(PermissionKind.PATH, resource, PermissionGrantScope.ONCE,
                    PermissionPersistence.MEMORY, Instant.now(), Optional.empty());
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            lastSignature = signature;
            lastClassification = classification;
            if (classification == CommandClassification.DANGEROUS || classification == CommandClassification.SENSITIVE) {
                throw new PermissionDeniedException(
                        new PermissionRequest(
                                "request-1",
                                PermissionRequestKind.COMMAND,
                                new PermissionResource.CommandResource(signature, classification),
                                "denied command",
                                context.toolUseId()
                        ),
                        Optional.of("denied command")
                );
            }
            PermissionResource resource = new PermissionResource.CommandResource(signature, classification);
            return new PermissionGrant(PermissionKind.COMMAND, resource, PermissionGrantScope.ONCE,
                    PermissionPersistence.MEMORY, Instant.now(), Optional.empty());
        }
    }

    private static final class DenyingCommandPermissionService implements PermissionService {
        private CommandSignature lastSignature;
        private CommandClassification lastClassification;

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            PermissionResource resource = new PermissionResource.PathResource(path, intent);
            return new PermissionGrant(PermissionKind.PATH, resource, PermissionGrantScope.ONCE,
                    PermissionPersistence.MEMORY, Instant.now(), Optional.empty());
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            lastSignature = signature;
            lastClassification = classification;
            throw new PermissionDeniedException(
                    new PermissionRequest(
                            "request-1",
                            PermissionRequestKind.COMMAND,
                            new PermissionResource.CommandResource(signature, classification),
                            "denied command",
                            context.toolUseId()
                    ),
                    Optional.of("denied command")
            );
        }
    }

    public static final class PrintMessage {
        public static void main(String[] args) {
            System.out.println("hello-from-helper");
        }
    }

    public static final class PrintCwd {
        public static void main(String[] args) {
            System.out.println(Path.of("").toAbsolutePath().normalize());
        }
    }

    public static final class FailWithExit {
        public static void main(String[] args) {
            System.err.println("helper-failed");
            System.exit(7);
        }
    }

    public static final class WriteMarker {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "executed");
        }
    }

    public static final class SleepThenWrite {
        public static void main(String[] args) throws Exception {
            Thread.sleep(5_000);
            Files.writeString(Path.of(args[0]), "too-late");
        }
    }
}
