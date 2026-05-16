package minicode.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.builtin.GrepFilesTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import minicode.workspace.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GrepFilesToolTest {
    @TempDir
    Path tempDir;

    @Test
    void textSearchReturnsRelativePathLineNumberAndPreview() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "alpha\nneedle here\nomega\n");

        ToolResult result = registry(allowingPermissionService()).execute(call(input("needle")), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("BASE: " + tempDir.toAbsolutePath().normalize()));
        assertTrue(result.content().contains("QUERY: needle"));
        assertTrue(result.content().contains("REGEX: false"));
        assertTrue(result.content().contains("MATCHES: 1"));
        assertTrue(result.content().contains("SCANNED_FILES: 1"));
        assertTrue(result.content().contains("notes.txt:2: needle here"));
    }

    @Test
    void patternAliasSearchesForStageFourCompatibility() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "needle here\n");
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("pattern", "needle");

        ToolResult result = registry(allowingPermissionService()).execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("QUERY: needle"));
        assertTrue(result.content().contains("notes.txt:1: needle here"));
    }

    @Test
    void caseInsensitiveByDefaultAndCaseSensitiveWhenRequested() throws IOException {
        Files.writeString(tempDir.resolve("case.txt"), "Needle\n");

        ToolResult defaultResult = registry(allowingPermissionService()).execute(call(input("needle")), context(tempDir));
        ObjectNode sensitiveInput = input("needle");
        sensitiveInput.put("caseSensitive", true);
        ToolResult sensitiveResult = registry(allowingPermissionService()).execute(call(sensitiveInput), context(tempDir));

        assertTrue(defaultResult.content().contains("MATCHES: 1"));
        assertTrue(sensitiveResult.content().contains("MATCHES: 0"));
    }

    @Test
    void regexSearchAndInvalidRegexBehavior() throws IOException {
        Files.writeString(tempDir.resolve("Main.java"), "class Main123 {}\n");
        ObjectNode regexInput = input("Main\\d+");
        regexInput.put("regex", true);
        ToolResult regexResult = registry(allowingPermissionService()).execute(call(regexInput), context(tempDir));

        ObjectNode invalidInput = input("[unterminated");
        invalidInput.put("regex", true);
        ToolResult invalidResult = registry(allowingPermissionService()).execute(call(invalidInput), context(tempDir));

        assertFalse(regexResult.error());
        assertTrue(regexResult.content().contains("Main.java:1: class Main123 {}"));
        assertTrue(invalidResult.error());
        assertTrue(invalidResult.content().contains("Invalid regex"));
    }

    @Test
    void outsideCwdPathRequestsPermissionAndDenyReturnsToolError() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "needle");
        CapturingPermissionService allow = new CapturingPermissionService(false);

        ToolResult allowed = registry(allow).execute(call(input(outside.toString(), "needle")), context(workspace));
        ToolResult denied = registry(new CapturingPermissionService(true)).execute(
                call(input(outside.toString(), "needle")),
                context(workspace)
        );

        assertFalse(allowed.error());
        assertTrue(allow.paths.contains(outside.toAbsolutePath().normalize()));
        assertEquals(PathIntent.SEARCH, allow.lastIntent);
        assertTrue(denied.error());
        assertTrue(denied.content().contains("search denied"));
        assertFalse(denied.content().contains("secret.txt:1"));
    }

    @Test
    void childSymlinkFileOutsideCwdRequiresPermissionBeforeRead() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside-secret.txt"), "needle secret");
        Path link = workspace.resolve("linked-secret.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symlink creation is unavailable: " + exception.getMessage());
        }

        ToolResult result = registry(new CapturingPermissionService(true)).execute(
                call(input("needle")),
                context(workspace)
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("search denied"));
        assertFalse(result.content().contains("linked-secret.txt:1"));
    }

    @Test
    void hiddenFilesAreSkippedByDefaultAndIncludedWhenRequested() throws IOException {
        Files.writeString(tempDir.resolve(".hidden.txt"), "needle");
        Files.writeString(tempDir.resolve("visible.txt"), "needle");
        ObjectNode includeHidden = input("needle");
        includeHidden.put("includeHidden", true);

        ToolResult defaultResult = registry(allowingPermissionService()).execute(call(input("needle")), context(tempDir));
        ToolResult hiddenResult = registry(allowingPermissionService()).execute(call(includeHidden), context(tempDir));

        assertFalse(defaultResult.content().contains(".hidden.txt"));
        assertTrue(defaultResult.content().contains("visible.txt"));
        assertTrue(hiddenResult.content().contains(".hidden.txt"));
    }

    @Test
    void binaryFilesAreSkipped() throws IOException {
        Files.write(tempDir.resolve("binary.bin"), new byte[]{'n', 'e', 0, 'e', 'd', 'l', 'e'});
        Files.writeString(tempDir.resolve("text.txt"), "needle");

        ToolResult result = registry(allowingPermissionService()).execute(call(input("needle")), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("text.txt:1"));
        assertFalse(result.content().contains("binary.bin"));
    }

    @Test
    void maxMatchesTruncatesOutput() throws IOException {
        Files.writeString(tempDir.resolve("many.txt"), "needle\nneedle\nneedle\n");
        ObjectNode input = input("needle");
        input.put("maxMatches", 2);

        ToolResult result = registry(allowingPermissionService()).execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("MATCHES: 2"));
        assertTrue(result.content().contains("TRUNCATED: true"));
        assertFalse(result.content().contains("many.txt:3"));
    }

    @Test
    void limitAliasTruncatesOutputForStageFourCompatibility() throws IOException {
        Files.writeString(tempDir.resolve("many.txt"), "needle\nneedle\nneedle\n");
        ObjectNode input = input("needle");
        input.put("limit", 2);

        ToolResult result = registry(allowingPermissionService()).execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("MATCHES: 2"));
        assertTrue(result.content().contains("TRUNCATED: true"));
    }

    @Test
    void filePathModeSearchesOnlyThatFileAndDirectoryModeSearchesRecursively() throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("root.txt"), "needle root");
        Files.writeString(sub.resolve("nested.txt"), "needle nested");

        ToolResult fileResult = registry(allowingPermissionService()).execute(
                call(input("root.txt", "needle")),
                context(tempDir)
        );
        ToolResult dirResult = registry(allowingPermissionService()).execute(call(input("needle")), context(tempDir));

        assertTrue(fileResult.content().contains("root.txt:1"));
        assertFalse(fileResult.content().contains("nested.txt"));
        assertTrue(dirResult.content().contains("root.txt:1"));
        assertTrue(dirResult.content().contains("sub" + java.io.File.separator + "nested.txt:1")
                || dirResult.content().contains("sub/nested.txt:1"));
    }

    @Test
    void nestedFilePathModeKeepsWorkspaceRelativePathInOutput() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("src").resolve("main"));
        Files.writeString(sub.resolve("App.java"), "needle app");

        ToolResult result = registry(allowingPermissionService()).execute(
                call(input(Path.of("src", "main", "App.java").toString(), "needle")),
                context(tempDir)
        );

        assertFalse(result.error());
        assertTrue(result.content().contains("src/main/App.java:1: needle app"));
    }

    @Test
    void cancellationDuringSearchPropagatesCancellation() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 50_000; i++) {
            content.append("line ").append(i).append('\n');
        }
        Files.writeString(tempDir.resolve("large.txt"), content.toString());
        CancellationToken token = CancellationToken.create();

        java.util.concurrent.CompletableFuture<ToolResult> future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> registry(allowingPermissionService()).execute(call(input("absent")), context(tempDir, token))
        );
        token.requestCancellation(CancellationSource.USER, "cancel grep");

        java.util.concurrent.ExecutionException exception = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        );
        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GrepFilesTool(permissionService, new WorkspacePathResolver()));
        return registry;
    }

    private static PermissionService allowingPermissionService() {
        return new PromptingPermissionService(PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE));
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "grep_files", input);
    }

    private static ObjectNode input(String query) {
        return JsonNodeFactory.instance.objectNode().put("query", query);
    }

    private static ObjectNode input(String path, String query) {
        return input(query).put("path", path);
    }

    private static ToolContext context(Path cwd) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }

    private static ToolContext context(Path cwd, CancellationToken token) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"), token);
    }

    private static PermissionGrant grant(PermissionResource resource) {
        return new PermissionGrant(PermissionKind.PATH, resource, PermissionGrantScope.ONCE,
                PermissionPersistence.MEMORY, Instant.now(), Optional.empty());
    }

    private static final class CapturingPermissionService implements PermissionService {
        private final boolean deny;
        private final java.util.List<Path> paths = new java.util.ArrayList<>();
        private PathIntent lastIntent;

        private CapturingPermissionService(boolean deny) {
            this.deny = deny;
        }

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            paths.add(path);
            lastIntent = intent;
            PermissionResource.PathResource resource = new PermissionResource.PathResource(path, intent);
            if (deny) {
                throw new PermissionDeniedException(request(resource, context), Optional.of("search denied"));
            }
            return grant(resource);
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static PermissionRequest request(PermissionResource resource, PermissionContext context) {
        return new PermissionRequest("request-1", PermissionRequestKind.PATH, resource, "path denied",
                context.toolUseId());
    }
}
