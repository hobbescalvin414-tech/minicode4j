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
import minicode.tools.builtin.WriteFileTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WriteFileToolTest {
    @TempDir
    Path tempDir;

    @Test
    void createNewFileInsideCwdWritesFileAndBuildsCreateReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = input("notes.txt", "  hello  \nworld\n");

        ToolResult result = registry.execute(call(input), context(workspace));

        Path writtenFile = workspace.resolve("notes.txt").toAbsolutePath().normalize();
        assertFalse(result.error());
        assertEquals("  hello  \nworld\n", Files.readString(writtenFile));
        assertTrue(result.content().contains("WROTE: " + writtenFile.toString().replace('\\', '/')));
        assertTrue(result.content().contains("OPERATION: CREATE"));
        assertTrue(permissionService.review().orElseThrow().path().equals(writtenFile));
        assertEquals(PermissionResource.EditOperation.CREATE, permissionService.review().orElseThrow().operation());
        assertTrue(permissionService.review().orElseThrow().summary().contains("Create file"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("--- /dev/null"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+++ b/"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+  hello  "));
    }

    @Test
    void overwriteExistingFileInsideCwdWritesFileAndBuildsOverwriteReview() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("app.txt"), "old\nline\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = input("app.txt", "new\nline\n");

        ToolResult result = registry.execute(call(input), context(workspace));

        assertFalse(result.error());
        assertEquals("new\nline\n", Files.readString(target));
        assertTrue(result.content().contains("OPERATION: OVERWRITE"));
        assertEquals(PermissionResource.EditOperation.OVERWRITE, permissionService.review().orElseThrow().operation());
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("--- a/"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+++ b/"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("-old"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+new"));
    }

    @Test
    void sameContentInsideExistingFileReturnsNoOpWithoutPermissionOrWrite() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("notes.txt"), "same\ncontent\n");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("notes.txt", "same\ncontent\n")), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("No changes needed"));
        assertEquals("same\ncontent\n", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void emptyExistingFileAndEmptyContentReturnsNoOpWithoutPermissionOrWrite() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("empty.txt"), "");
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);

        ToolResult result = registry.execute(call(input("empty.txt", "")), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("No changes needed"));
        assertEquals("", Files.readString(target));
        assertTrue(permissionService.review().isEmpty());
    }

    @Test
    void outsideCwdPathStillGoesThroughEditPermissionReviewBeforeWrite() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(tempDir.resolve("outside"));
        Path outsideTarget = tempDir.resolve("outside").resolve("notes.txt").toAbsolutePath().normalize();
        CapturingEditPermissionService permissionService = allowingEditPermissionService();
        ToolRegistry registry = registry(permissionService);
        ObjectNode input = input(outsideTarget.toString(), "outside\n");

        ToolResult result = registry.execute(call(input), context(workspace));

        assertFalse(result.error());
        assertEquals(outsideTarget, permissionService.review().orElseThrow().path());
        assertTrue(Files.exists(outsideTarget));
    }

    @Test
    void permissionDenyReturnsToolErrorAndDoesNotWriteFile() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path target = workspace.resolve("denied.txt");
        ToolRegistry registry = registry(new DenyingEditPermissionService("Use a narrower file target"));

        ToolResult result = registry.execute(call(input("denied.txt", "content\n")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Use a narrower file target"));
        assertFalse(Files.exists(target));
    }

    @Test
    void parentDirectoryMissingFailsWithoutImplicitCreation() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        ToolRegistry registry = registry(allowingEditPermissionService());

        ToolResult result = registry.execute(call(input("missing/child.txt", "content\n")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Parent path does not exist"));
        assertFalse(Files.exists(workspace.resolve("missing")));
    }

    @Test
    void writingToDirectoryPathFails() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("folder"));
        ToolRegistry registry = registry(allowingEditPermissionService());

        ToolResult result = registry.execute(call(input("folder", "content\n")), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Expected file but found directory"));
    }

    @Test
    void cancellationBeforeWritePreventsFileCreation() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        CancellationToken token = CancellationToken.create();
        ToolRegistry registry = registry(new CancellingEditPermissionService(token));

        java.util.concurrent.CompletableFuture<ToolResult> future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> registry.execute(call(input("cancelled.txt", "content\n")), context(workspace, token))
        );

        java.util.concurrent.ExecutionException exception = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        );

        assertInstanceOf(CancellationRequestedException.class, exception.getCause());
        assertFalse(Files.exists(workspace.resolve("cancelled.txt")));
    }

    @Test
    void validateInputPreservesContentWhitespaceAndRequiresPath() {
        WriteFileTool tool = new WriteFileTool(allowingEditPermissionService(), new minicode.workspace.WorkspacePathResolver());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("path", "  notes.txt  ");
        input.put("content", "  keep surrounding spaces  ");

        var validation = tool.validateInput(input);

        assertTrue(validation.valid());
        assertEquals("notes.txt", validation.normalizedInput().orElseThrow().get("path").asText());
        assertEquals("  keep surrounding spaces  ", validation.normalizedInput().orElseThrow().get("content").asText());
    }

    private static ToolRegistry registry(PermissionService permissionService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool(permissionService, new minicode.workspace.WorkspacePathResolver()));
        return registry;
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "write_file", input);
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
        return new CapturingEditPermissionService(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
    }

    private static PermissionGrant grant(PermissionResource.EditResource resource) {
        return new PermissionGrant(
                PermissionKind.EDIT,
                resource,
                PermissionGrantScope.ONCE,
                PermissionPersistence.MEMORY,
                Instant.now(),
                Optional.empty()
        );
    }

    private static final class CapturingEditPermissionService implements PermissionService {
        private final PermissionPromptResult result;
        private PermissionResource.EditResource review;

        private CapturingEditPermissionService(PermissionPromptResult result) {
            this.result = result;
        }

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException("write_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("write_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            review = resource;
            if (!result.allowed()) {
                throw new PermissionDeniedException(
                        new PermissionRequest(
                                "request-1",
                                PermissionRequestKind.EDIT,
                                resource,
                                "Allow file edit",
                                context.toolUseId()
                        ),
                        result.choiceKey(),
                        result.feedback()
                );
            }
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
            throw new UnsupportedOperationException("write_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("write_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            throw new PermissionDeniedException(
                    new PermissionRequest(
                            "request-1",
                            PermissionRequestKind.EDIT,
                            resource,
                            "Allow file edit",
                            context.toolUseId()
                    ),
                    Optional.of(feedback)
            );
        }
    }

    private static final class CancellingEditPermissionService implements PermissionService {
        private final CancellationToken token;

        private CancellingEditPermissionService(CancellationToken token) {
            this.token = token;
        }

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException("write_file should not request path permission");
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException("write_file should not request command permission");
        }

        @Override
        public PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
            token.requestCancellation(CancellationSource.USER, "cancel write");
            return grant(resource);
        }
    }
}
