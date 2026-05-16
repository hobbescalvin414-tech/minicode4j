package minicode.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.builtin.PatchFileTool;
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

class PatchFileToolTest {
    @TempDir
    Path tempDir;

    @Test
    void dryRunSuccessWritesAfterAllow() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\nbeta\ngamma\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt",
                replacement("alpha", "ALPHA", false),
                replacement("gamma", "GAMMA", false))), context(workspace));

        assertFalse(result.error());
        assertEquals("ALPHA\nbeta\nGAMMA\n", Files.readString(target));
        assertTrue(result.content().contains("Patched app.txt with 2 replacement(s)"));
        assertEquals(PermissionResource.EditOperation.PATCH, permissionService.review().orElseThrow().operation());
    }

    @Test
    void dryRunFailureDoesNotEnterReviewOrWrite() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt",
                replacement("missing", "new", false))), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Replacement 1 search text not found"));
        assertEquals("alpha\n", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void permissionDenyLeavesFileUnchanged() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Patch is too broad"));

        ToolResult result = registry.execute(call(input("app.txt",
                replacement("alpha", "beta", false))), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Patch is too broad"));
        assertEquals("alpha\n", Files.readString(target));
    }

    @Test
    void denyWithFeedbackReturnsFeedbackToModel() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Split this patch"));

        ToolResult result = registry.execute(call(input("app.txt",
                replacement("alpha", "beta", false))), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Split this patch"));
    }

    @Test
    void noOpPatchSkipsReviewAndPermission() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt",
                replacement("alpha", "alpha", false))), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("No changes needed"));
        assertEquals("alpha\n", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void absolutePathOutsideCwdStillUsesResolverAndEditReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "alpha\n").toAbsolutePath().normalize();
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input(outside.toString(),
                replacement("alpha", "beta", false))), context(workspace));

        assertFalse(result.error());
        assertEquals("beta\n", Files.readString(outside));
        assertEquals(outside, permissionService.review().orElseThrow().path());
    }

    @Test
    void cancellationAfterReviewPreventsWrite() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        CancellationToken token = CancellationToken.create();
        ToolRegistry registry = registry(new CancellingEditPermissionService(token));

        var future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> registry.execute(call(input("app.txt",
                        replacement("alpha", "beta", false))), context(workspace, token))
        );

        var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
        assertEquals("alpha\n", Files.readString(target));
    }

    @Test
    void directoryPathReturnsToolError() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("folder"));
        ToolRegistry registry = registry(allowingEditPermissionService());

        ToolResult result = registry.execute(call(input("folder",
                replacement("alpha", "beta", false))), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Expected file but found directory"));
    }

    @Test
    void usesInternalExactReplacementPatchFormatWithoutExternalShell() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "one\ntwo\none\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt",
                replacement("one", "ONE", true))), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("#1 replaceAll"));
        assertEquals(PermissionResource.EditOperation.PATCH, permissionService.review().orElseThrow().operation());
        assertEquals("ONE\ntwo\nONE\n", Files.readString(workspace.resolve("app.txt")));
    }

    @Test
    void omittedReplaceAllDefaultsToReplaceOnce() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "one\ntwo\none\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ObjectNode replacement = JsonNodeFactory.instance.objectNode()
                .put("search", "one")
                .put("replace", "ONE");
        ToolResult result = registry.execute(call(input("app.txt", replacement)), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("#1 replaceOnce"));
        assertEquals("ONE\ntwo\none\n", Files.readString(workspace.resolve("app.txt")));
        assertEquals(PermissionResource.EditOperation.PATCH, permissionService.review().orElseThrow().operation());
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PatchFileTool(permissionService, new WorkspacePathResolver()));
        return registry;
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "patch_file", input);
    }

    private static ObjectNode input(String path, ObjectNode... replacements) {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("path", path);
        ArrayNode array = input.putArray("replacements");
        for (ObjectNode replacement : replacements) {
            array.add(replacement);
        }
        return input;
    }

    private static ObjectNode replacement(String search, String replace, boolean replaceAll) {
        return JsonNodeFactory.instance.objectNode()
                .put("search", search)
                .put("replace", replace)
                .put("replaceAll", replaceAll);
    }

    private static ToolContext context(Path cwd) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }

    private static ToolContext context(Path cwd, CancellationToken token) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"), token);
    }

    private static CapturingEditPermissionService allowingEditPermissionService() {
        return new CapturingEditPermissionService();
    }

    private static PermissionGrant grant(PermissionResource.EditResource resource) {
        return new PermissionGrant(PermissionKind.EDIT, resource, PermissionGrantScope.ONCE,
                PermissionPersistence.MEMORY, Instant.now(), Optional.empty());
    }

    private static final class CapturingEditPermissionService implements PermissionService {
        private PermissionResource.EditResource review;

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException("patch_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("patch_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            review = resource;
            return grant(resource);
        }

        private Optional<PermissionResource.EditResource> review() {
            return Optional.ofNullable(review);
        }
    }

    private static final class DenyingEditPermissionService implements PermissionService {
        private final String feedback;

        private DenyingEditPermissionService(String feedback) {
            this.feedback = feedback;
        }

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException("patch_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("patch_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            throw new PermissionDeniedException(new PermissionRequest(
                    "request-1",
                    PermissionRequestKind.EDIT,
                    resource,
                    "Allow file edit",
                    context.toolUseId()
            ), Optional.of(feedback));
        }
    }

    private static final class CancellingEditPermissionService implements PermissionService {
        private final CancellationToken token;

        private CancellingEditPermissionService(CancellationToken token) {
            this.token = token;
        }

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException("patch_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("patch_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            token.requestCancellation(CancellationSource.USER, "cancel patch");
            return grant(resource);
        }
    }
}
