package minicode.edit;

import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FileWriteServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createFileBuildsCreateReviewAndWritesAfterPermission() throws IOException {
        CapturingPermissionService permissionService = new CapturingPermissionService(PermissionPromptResult.allow(
                "allow_once",
                PermissionDecision.ALLOW_ONCE
        ));
        FileWriteService service = new FileWriteService(permissionService);
        Path target = tempDir.resolve("created.txt");

        FileWriteResult result = service.apply(target, "created.txt", "hello\n", Optional.of("tool-use-1"),
                permissionContext(), () -> {
                });

        assertFalse(result.noOp());
        assertEquals(PermissionResource.EditOperation.CREATE, result.operation().orElseThrow());
        assertEquals("hello\n", Files.readString(target));
        assertEquals(PermissionResource.EditOperation.CREATE, permissionService.review().orElseThrow().operation());
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("--- /dev/null"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+hello"));
    }

    @Test
    void overwriteFileBuildsOverwriteReviewAndWritesAfterPermission() throws IOException {
        Path target = Files.writeString(tempDir.resolve("existing.txt"), "old\n");
        CapturingPermissionService permissionService = new CapturingPermissionService(PermissionPromptResult.allow(
                "allow_once",
                PermissionDecision.ALLOW_ONCE
        ));
        FileWriteService service = new FileWriteService(permissionService);

        FileWriteResult result = service.apply(target, "existing.txt", "new\n", Optional.of("tool-use-1"),
                permissionContext(), () -> {
                });

        assertFalse(result.noOp());
        assertEquals(PermissionResource.EditOperation.OVERWRITE, result.operation().orElseThrow());
        assertEquals("new\n", Files.readString(target));
        assertEquals(PermissionResource.EditOperation.OVERWRITE, permissionService.review().orElseThrow().operation());
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("-old"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+new"));
    }

    @Test
    void reviewedReplacementUsesCallerProvidedOperationAndSummary() throws IOException {
        Path target = Files.writeString(tempDir.resolve("editable.txt"), "old\n");
        CapturingPermissionService permissionService = new CapturingPermissionService(PermissionPromptResult.allow(
                "allow_once",
                PermissionDecision.ALLOW_ONCE
        ));
        FileWriteService service = new FileWriteService(permissionService);

        FileWriteResult result = service.applyReviewedReplacement(
                target,
                "editable.txt",
                PermissionResource.EditOperation.EDIT,
                "Replace exact text in editable.txt",
                "new\n",
                Optional.of("tool-use-1"),
                permissionContext(),
                () -> {
                }
        );

        assertFalse(result.noOp());
        assertEquals(PermissionResource.EditOperation.EDIT, result.operation().orElseThrow());
        assertEquals("new\n", Files.readString(target));
        assertEquals(PermissionResource.EditOperation.EDIT, permissionService.review().orElseThrow().operation());
        assertEquals("Replace exact text in editable.txt", permissionService.review().orElseThrow().summary());
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("-old"));
        assertTrue(permissionService.review().orElseThrow().diffPreview().contains("+new"));
    }

    @Test
    void noOpSkipsPermissionAndDoesNotRewriteFile() throws IOException {
        Path target = Files.writeString(tempDir.resolve("same.txt"), "same\n");
        CapturingPermissionService permissionService = new CapturingPermissionService(PermissionPromptResult.allow(
                "allow_once",
                PermissionDecision.ALLOW_ONCE
        ));
        FileWriteService service = new FileWriteService(permissionService);

        FileWriteResult result = service.apply(target, "same.txt", "same\n", Optional.of("tool-use-1"),
                permissionContext(), () -> {
                });

        assertTrue(result.noOp());
        assertEquals("No changes needed for same.txt", result.message());
        assertTrue(permissionService.review().isEmpty());
        assertEquals("same\n", Files.readString(target));
    }

    @Test
    void denyWithFeedbackPropagatesFeedbackAndDoesNotWriteFile() throws IOException {
        Path target = tempDir.resolve("denied.txt");
        CapturingPermissionService permissionService = new CapturingPermissionService(
                PermissionPromptResult.deny("deny_feedback", PermissionDecision.DENY_WITH_FEEDBACK, "Need a narrower file")
        );
        FileWriteService service = new FileWriteService(permissionService);

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.apply(target, "denied.txt", "content\n", Optional.of("tool-use-1"),
                        permissionContext(), () -> {
                        }));

        assertEquals(Optional.of("Need a narrower file"), exception.feedback());
        assertFalse(Files.exists(target));
        assertEquals(PermissionResource.EditOperation.CREATE, permissionService.review().orElseThrow().operation());
    }

    @Test
    void allowHappensBeforeWritingToDisk() throws IOException {
        Path target = tempDir.resolve("guarded.txt");
        PermissionService permissionService = new PermissionService() {
            @Override
            public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                                 PermissionContext context) {
                throw new UnsupportedOperationException();
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
                        Optional.of("blocked")
                );
            }
        };
        FileWriteService service = new FileWriteService(permissionService);

        assertThrows(PermissionDeniedException.class, () ->
                service.apply(target, "guarded.txt", "content\n", Optional.of("tool-use-1"), permissionContext(),
                        () -> {
                        }));

        assertFalse(Files.exists(target));
    }

    private static PermissionContext permissionContext() {
        return new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
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

    private static final class CapturingPermissionService implements PermissionService {
        private final PermissionPromptResult result;
        private PermissionResource.EditResource review;

        private CapturingPermissionService(PermissionPromptResult result) {
            this.result = result;
        }

        @Override
        public PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                             PermissionContext context) {
            throw new UnsupportedOperationException();
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
}
