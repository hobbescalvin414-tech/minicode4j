package minicode.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.builtin.EditFileTool;
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

class EditFileToolTest {
    @TempDir
    Path tempDir;

    @Test
    void singleOldTextReplacementWritesAfterReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\nbeta\ngamma\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt", "beta", "BETA")), context(workspace));

        assertFalse(result.error());
        assertEquals("alpha\nBETA\ngamma\n", Files.readString(target));
        assertTrue(result.content().contains("EDITED: " + displayPath(target)));
        assertEquals(PermissionResource.EditOperation.EDIT, permissionService.review().orElseThrow().operation());
        assertTrue(permissionService.review().orElseThrow().summary().contains("Replace text"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("-beta"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+BETA"));
    }

    @Test
    void oldTextNotFoundReturnsToolErrorWithoutReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt", "missing", "new")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Text not found"));
        assertEquals("alpha\n", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void oldTextMultipleMatchesReplaceFirstByDefault() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "same\nsame\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt", "same", "other")), context(workspace));

        assertFalse(result.error());
        assertEquals("other\nsame\n", Files.readString(target));
        assertTrue(permissionService.review().isPresent());
    }

    @Test
    void replaceAllReplacesEveryOldTextOccurrence() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "same\nsame\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = input("app.txt", "same", "other");
        input.put("replaceAll", true);

        ToolResult result = registry.execute(call(input), context(workspace));

        assertFalse(result.error());
        assertEquals("other\nother\n", Files.readString(target));
        assertTrue(permissionService.review().isPresent());
    }

    @Test
    void noOpReplacementSkipsReviewAndPermission() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt", "alpha", "alpha")), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("No changes needed"));
        assertEquals("alpha\n", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void permissionDenyReturnsToolErrorAndLeavesFileUnchanged() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Do not edit this file"));

        ToolResult result = registry.execute(call(input("app.txt", "alpha", "beta")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Do not edit this file"));
        assertEquals("alpha\n", Files.readString(target));
    }

    @Test
    void denyWithFeedbackReturnsFeedbackToModel() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Use a smaller replacement"));

        ToolResult result = registry.execute(call(input("app.txt", "alpha", "beta")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Use a smaller replacement"));
    }

    @Test
    void absolutePathOutsideCwdStillUsesResolverAndEditReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "alpha\n").toAbsolutePath().normalize();
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input(outside.toString(), "alpha", "beta")), context(workspace));

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
                () -> registry.execute(call(input("app.txt", "alpha", "beta")), context(workspace, token))
        );

        var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
        assertEquals("alpha\n", Files.readString(target));
    }

    @Test
    void usesFileWriteServiceReviewBoundary() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "alpha\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        registry.execute(call(input("app.txt", "alpha", "beta")), context(workspace));

        PermissionResource.EditResource review = permissionService.review().orElseThrow();
        assertEquals(PermissionResource.EditOperation.EDIT, review.operation());
        assertTrue(review.diffPreview().contains("--- a/"));
        assertTrue(review.diffPreview().contains("+++ b/"));
        assertTrue(review.diffRef().orElseThrow().startsWith("sha256:"));
    }

    @Test
    void directoryPathReturnsToolError() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("folder"));
        ToolRegistry registry = registry(allowingEditPermissionService());

        ToolResult result = registry.execute(call(input("folder", "alpha", "beta")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Expected file but found directory"));
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EditFileTool(permissionService, new WorkspacePathResolver()));
        return registry;
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "edit_file", input);
    }

    private static ObjectNode input(String path, String oldText, String newText) {
        return JsonNodeFactory.instance.objectNode()
                .put("path", path)
                .put("oldText", oldText)
                .put("newText", newText);
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

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static final class CapturingEditPermissionService implements PermissionService {
        private PermissionResource.EditResource review;

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException("edit_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("edit_file should not request command permission");
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
            throw new UnsupportedOperationException("edit_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("edit_file should not request command permission");
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
            throw new UnsupportedOperationException("edit_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("edit_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            token.requestCancellation(CancellationSource.USER, "cancel edit");
            return grant(resource);
        }
    }
}
