package minicode.workspace;

import minicode.permissions.model.PathIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePathResolverTest {
    @TempDir
    Path tempDir;

    private final WorkspacePathResolver resolver = new WorkspacePathResolver();

    @Test
    void resolvesRelativePathInsideCwd() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("notes.txt"), "hello");

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                "notes.txt",
                PathIntent.READ,
                true,
                false
        ));

        assertEquals(WorkspaceBoundary.INSIDE_CWD, result.resolvedPath().boundary());
        assertEquals(workspace.resolve("notes.txt").toAbsolutePath().normalize(), result.resolvedPath().normalizedPath());
        assertTrue(result.exists());
        assertTrue(result.resolvedPath().realPath().isPresent());
    }

    @Test
    void resolvesAbsolutePathInsideCwd() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "hello");

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                file.toAbsolutePath().toString(),
                PathIntent.READ,
                true,
                false
        ));

        assertEquals(WorkspaceBoundary.INSIDE_CWD, result.resolvedPath().boundary());
        assertEquals(file.toAbsolutePath().normalize(), result.resolvedPath().normalizedPath());
    }

    @Test
    void identifiesAbsolutePathOutsideCwd() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                outside.toString(),
                PathIntent.READ,
                true,
                false
        ));

        assertEquals(WorkspaceBoundary.OUTSIDE_CWD, result.resolvedPath().boundary());
    }

    @Test
    void normalizesDotDotAndIdentifiesEscapeOutsideCwd() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                Path.of("..", "outside.txt").toString(),
                PathIntent.READ,
                true,
                false
        ));

        assertEquals(WorkspaceBoundary.OUTSIDE_CWD, result.resolvedPath().boundary());
        assertEquals(outside.toAbsolutePath().normalize(), result.resolvedPath().normalizedPath());
    }

    @Test
    void missingReadTargetThrowsPathException() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        WorkspacePathException exception = assertThrows(WorkspacePathException.class, () -> resolver.resolve(
                new WorkspacePathRequest(workspace, "missing.txt", PathIntent.READ, true, false)
        ));

        assertTrue(exception.getMessage().contains("Path does not exist"));
    }

    @Test
    void targetOrExistingParentAllowsMissingTargetWithExistingParent() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path parent = Files.createDirectory(workspace.resolve("src"));

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                Path.of("src", "new.txt").toString(),
                PathIntent.WRITE,
                WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT
        ));

        assertFalse(result.exists());
        assertEquals(workspace.resolve("src").resolve("new.txt").toAbsolutePath().normalize(),
                result.resolvedPath().normalizedPath());
        assertEquals(WorkspaceBoundary.INSIDE_CWD, result.resolvedPath().boundary());
        assertTrue(result.parentRealPath().isPresent());
        assertEquals(parent.toRealPath(), result.parentRealPath().orElseThrow());
    }

    @Test
    void targetOrExistingParentRejectsMissingParent() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        WorkspacePathException exception = assertThrows(WorkspacePathException.class, () -> resolver.resolve(
                new WorkspacePathRequest(
                        workspace,
                        Path.of("missing", "new.txt").toString(),
                        PathIntent.WRITE,
                        WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT
                )
        ));

        assertTrue(exception.getMessage().contains("Parent path does not exist"));
    }

    @Test
    void directoryRejectedWhenDirectoryNotAllowed() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("dir"));

        WorkspacePathException exception = assertThrows(WorkspacePathException.class, () -> resolver.resolve(
                new WorkspacePathRequest(workspace, "dir", PathIntent.READ, true, false)
        ));

        assertTrue(exception.getMessage().contains("Expected file"));
    }

    @Test
    void symlinkEscapingCwdIsConservativelyOutsideWhenSupported() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");
        Path link = workspace.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            System.out.println("Skipping symlink assertion: symlink creation is unavailable on this platform: "
                    + exception.getMessage());
            return;
        }

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                "link.txt",
                PathIntent.READ,
                true,
                false
        ));

        assertEquals(WorkspaceBoundary.OUTSIDE_CWD, result.resolvedPath().boundary());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDriveAbsolutePathOutsideCwdIsIdentified() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");

        WorkspacePathResult result = resolver.resolve(new WorkspacePathRequest(
                workspace,
                outside.toAbsolutePath().toString(),
                PathIntent.READ,
                true,
                false
        ));

        assertEquals(WorkspaceBoundary.OUTSIDE_CWD, result.resolvedPath().boundary());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uncPathBehaviorIsAtLeastRejectedOrClassifiedOnWindows() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        assertThrows(WorkspacePathException.class, () -> resolver.resolve(new WorkspacePathRequest(
                workspace,
                "\\\\server\\share\\missing.txt",
                PathIntent.READ,
                true,
                false
        )));
    }
}
