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
import minicode.tools.builtin.ModifyFileTool;
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

class ModifyFileToolTest {
    @TempDir
    Path tempDir;

    @Test
    void fullFileReplacementWritesAfterReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "old\ncontent\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt", "new\ncontent\n")), context(workspace));

        assertFalse(result.error());
        assertEquals("new\ncontent\n", Files.readString(target));
        assertTrue(result.content().contains("MODIFIED: " + displayPath(target)));
        assertEquals(PermissionResource.EditOperation.MODIFY, permissionService.review().orElseThrow().operation());
        assertTrue(permissionService.review().orElseThrow().summary().contains("Modify file"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("-old"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+new"));
    }

    @Test
    void noOpSkipsReviewAndPermission() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "same\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("app.txt", "same\n")), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("No changes needed"));
        assertEquals("same\n", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void permissionDenyLeavesFileUnchanged() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "old\n");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Do not replace whole file"));

        ToolResult result = registry.execute(call(input("app.txt", "new\n")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Do not replace whole file"));
        assertEquals("old\n", Files.readString(target));
    }

    @Test
    void denyWithFeedbackReturnsFeedbackToModel() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "old\n");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Use edit_file instead"));

        ToolResult result = registry.execute(call(input("app.txt", "new\n")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Use edit_file instead"));
    }

    @Test
    void absolutePathOutsideCwdStillUsesResolverAndEditReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "old\n").toAbsolutePath().normalize();
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input(outside.toString(), "new\n")), context(workspace));

        assertFalse(result.error());
        assertEquals("new\n", Files.readString(outside));
        assertEquals(outside, permissionService.review().orElseThrow().path());
    }

    @Test
    void missingFileCreatesThroughReviewedReplacement() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("missing.txt", "content\n")), context(workspace));

        assertFalse(result.error());
        assertEquals("content\n", Files.readString(workspace.resolve("missing.txt")));
        assertEquals(PermissionResource.EditOperation.MODIFY, permissionService.review().orElseThrow().operation());
        assertFalse(permissionService.review().orElseThrow().originalExists());
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("--- /dev/null"));
    }

    @Test
    void directoryPathReturnsToolError() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("folder"));
        ToolRegistry registry = registry(allowingEditPermissionService());

        ToolResult result = registry.execute(call(input("folder", "content\n")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Expected file but found directory"));
    }

    @Test
    void instructionFieldIsIgnoredAndDoesNotCreateSmartRewriteSemantics() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "old\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = input("app.txt", "literal\n");
        input.put("instruction", "change old to smart rewrite");

        ToolResult result = registry.execute(call(input), context(workspace));

        assertFalse(result.error());
        assertEquals("literal\n", Files.readString(target));
        assertEquals(PermissionResource.EditOperation.MODIFY, permissionService.review().orElseThrow().operation());
    }

    @Test
    void cancellationAfterReviewPreventsWrite() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "old\n");
        CancellationToken token = CancellationToken.create();
        ToolRegistry registry = registry(new CancellingEditPermissionService(token));

        var future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> registry.execute(call(input("app.txt", "new\n")), context(workspace, token))
        );

        var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
        assertEquals("old\n", Files.readString(target));
    }

    @Test
    void usesFileWriteServiceReviewBoundary() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("app.txt"), "old\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        registry.execute(call(input("app.txt", "new\n")), context(workspace));

        PermissionResource.EditResource review = permissionService.review().orElseThrow();
        assertEquals(PermissionResource.EditOperation.MODIFY, review.operation());
        assertTrue(review.diffPreview().contains("--- a/"));
        assertTrue(review.diffPreview().contains("+++ b/"));
        assertTrue(review.diffRef().orElseThrow().startsWith("sha256:"));
    }

    @Test
    void metadataAndSchemaDescribeCreateOrReplaceSemantics() {
        ModifyFileTool tool = new ModifyFileTool(allowingEditPermissionService(), new WorkspacePathResolver());

        assertTrue(tool.metadata().description().contains("Create or replace"));
        assertTrue(tool.inputSchema().get("properties").get("path").get("description").asText()
                .contains("create or replace"));
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ModifyFileTool(permissionService, new WorkspacePathResolver()));
        return registry;
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "modify_file", input);
    }

    private static ObjectNode input(String path, String content) {
        return JsonNodeFactory.instance.objectNode()
                .put("path", path)
                .put("content", content);
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
            throw new UnsupportedOperationException("modify_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("modify_file should not request command permission");
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
            throw new UnsupportedOperationException("modify_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("modify_file should not request command permission");
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
            throw new UnsupportedOperationException("modify_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("modify_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            token.requestCancellation(CancellationSource.USER, "cancel modify");
            return grant(resource);
        }
    }
}
