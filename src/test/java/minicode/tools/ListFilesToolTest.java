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
import minicode.tools.builtin.ListFilesTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import minicode.workspace.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ListFilesToolTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultListsCwdDirectoryThroughRegistry() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.createDirectory(tempDir.resolve("src"));
        ToolResult result = registry(allowingPermissionService()).execute(call(input()), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("BASE: " + tempDir.toAbsolutePath().normalize()));
        assertTrue(result.content().contains("COUNT: 2"));
        assertTrue(result.content().contains("TRUNCATED: false"));
        assertTrue(result.content().contains("a.txt"));
        assertTrue(result.content().contains("src/"));
    }

    @Test
    void relativePathUsesWorkspaceResolver() throws IOException {
        Path module = Files.createDirectory(tempDir.resolve("module"));
        Files.writeString(module.resolve("Main.java"), "class Main {}");

        ToolResult result = registry(allowingPermissionService()).execute(call(input("module")), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("BASE: " + module.toAbsolutePath().normalize()));
        assertTrue(result.content().contains("Main.java"));
    }

    @Test
    void outsideCwdDirectoryRequestsPermission() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        CapturingPermissionService permissionService = new CapturingPermissionService(false);

        ToolResult result = registry(permissionService).execute(call(input(outside.toString())), context(workspace));

        assertFalse(result.error());
        assertTrue(permissionService.paths.contains(outside.toAbsolutePath().normalize()));
        assertEquals(PathIntent.LIST, permissionService.lastIntent);
        assertTrue(result.content().contains("secret.txt"));
    }

    @Test
    void outsideCwdDirectoryListAuthorizationCoversChildrenForCurrentToolCall() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "a");
        Path nested = Files.createDirectory(outside.resolve("nested"));
        Files.writeString(nested.resolve("b.txt"), "b");
        CapturingPermissionService permissionService = new CapturingPermissionService(false);

        ToolResult result = registry(permissionService).execute(call(input(outside.toString())), context(workspace));

        assertFalse(result.error());
        assertEquals(List.of(outside.toAbsolutePath().normalize()), permissionService.paths);
        assertTrue(result.content().contains("a.txt"));
        assertTrue(result.content().contains("nested/"));
        assertTrue(result.content().contains("nested/b.txt"));
    }

    @Test
    void outsideCwdDirectoryListAuthorizationDoesNotCoverSymlinkEscapes() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path escaped = Files.createDirectory(tempDir.resolve("escaped"));
        Files.writeString(escaped.resolve("secret.txt"), "secret");
        Path link = outside.resolve("escaped-link");
        try {
            Files.createSymbolicLink(link, escaped);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symlink creation is unavailable: " + exception.getMessage());
        }
        CapturingPermissionService permissionService = new CapturingPermissionService(false);

        ToolResult result = registry(permissionService).execute(call(input(outside.toString())), context(workspace));

        assertFalse(result.error());
        assertEquals(List.of(outside.toAbsolutePath().normalize(), link.toAbsolutePath().normalize()),
                permissionService.paths);
        assertTrue(result.content().contains("escaped-link/"));
        assertTrue(result.content().contains("escaped-link/secret.txt"));
    }

    @Test
    void outsideCwdPermissionDenyReturnsToolErrorAndDoesNotTraverse() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");

        ToolResult result = registry(new DenyingPathPermissionService()).execute(
                call(input(outside.toString())),
                context(workspace)
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("list denied"));
        assertFalse(result.content().contains("secret.txt"));
    }

    @Test
    void childSymlinkDirectoryOutsideCwdRequiresPermissionBeforeTraversal() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("linked-outside");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symlink creation is unavailable: " + exception.getMessage());
        }

        ToolResult result = registry(new DenyingPathPermissionService()).execute(call(input()), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("list denied"));
        assertFalse(result.content().contains("secret.txt"));
    }

    @Test
    void hiddenEntriesAreSkippedByDefaultAndIncludedWhenRequested() throws IOException {
        Files.writeString(tempDir.resolve(".env"), "secret");
        Files.writeString(tempDir.resolve("visible.txt"), "visible");

        ToolResult defaultResult = registry(allowingPermissionService()).execute(call(input()), context(tempDir));
        ObjectNode includeHidden = input();
        includeHidden.put("includeHidden", true);
        ToolResult hiddenResult = registry(allowingPermissionService()).execute(call(includeHidden), context(tempDir));

        assertFalse(defaultResult.content().contains(".env"));
        assertTrue(defaultResult.content().contains("visible.txt"));
        assertTrue(hiddenResult.content().contains(".env"));
    }

    @Test
    void maxDepthLimitsTraversal() throws IOException {
        Path level1 = Files.createDirectory(tempDir.resolve("level1"));
        Path level2 = Files.createDirectory(level1.resolve("level2"));
        Files.writeString(level2.resolve("deep.txt"), "deep");
        ObjectNode input = input();
        input.put("maxDepth", 1);

        ToolResult result = registry(allowingPermissionService()).execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("level1/"));
        assertFalse(result.content().contains("level1/level2/"));
        assertFalse(result.content().contains("deep.txt"));
    }

    @Test
    void depthAliasIsAcceptedForStageFourCompatibility() throws IOException {
        Path level1 = Files.createDirectory(tempDir.resolve("level1"));
        Files.createDirectory(level1.resolve("level2"));
        ObjectNode input = input();
        input.put("depth", 1);

        ToolResult result = registry(allowingPermissionService()).execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("level1/"));
        assertFalse(result.content().contains("level1/level2/"));
    }

    @Test
    void limitTruncatesOutput() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");
        ObjectNode input = input();
        input.put("limit", 1);

        ToolResult result = registry(allowingPermissionService()).execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("COUNT: 1"));
        assertTrue(result.content().contains("TRUNCATED: true"));
    }

    @Test
    void missingPathAndFilePathReturnToolErrors() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "content");
        ToolRegistry registry = registry(allowingPermissionService());

        ToolResult missing = registry.execute(call(input("missing")), context(tempDir));
        ToolResult file = registry.execute(call(input("file.txt")), context(tempDir));

        assertTrue(missing.error());
        assertTrue(missing.content().contains("Path does not exist"));
        assertTrue(file.error());
        assertTrue(file.content().contains("Expected directory"));
    }

    @Test
    void cancellationDuringListingPropagatesCancellation() throws Exception {
        for (int i = 0; i < 5000; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".txt"), "content");
        }
        CancellationToken token = CancellationToken.create();
        ToolRegistry registry = registry(allowingPermissionService());

        java.util.concurrent.CompletableFuture<ToolResult> future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> registry.execute(call(input()), context(tempDir, token))
        );
        token.requestCancellation(CancellationSource.USER, "cancel list");

        java.util.concurrent.ExecutionException exception = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        );
        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(permissionService, new WorkspacePathResolver()));
        return registry;
    }

    private static PermissionService allowingPermissionService() {
        return new PromptingPermissionService(PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE));
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "list_files", input);
    }

    private static ObjectNode input() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode input(String path) {
        return input().put("path", path);
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

    private static class CapturingPermissionService implements PermissionService {
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
                throw new PermissionDeniedException(request(resource, context), Optional.of("list denied"));
            }
            return grant(resource);
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class DenyingPathPermissionService extends CapturingPermissionService {
        private DenyingPathPermissionService() {
            super(true);
        }
    }

    private static PermissionRequest request(PermissionResource resource, PermissionContext context) {
        return new PermissionRequest("request-1", PermissionRequestKind.PATH, resource, "path denied",
                context.toolUseId());
    }
}
