package minicode.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record WorkspacePathResult(ResolvedWorkspacePath resolvedPath,
                                  boolean exists,
                                  Optional<Path> parentRealPath) {
    public WorkspacePathResult {
        resolvedPath = Objects.requireNonNull(resolvedPath, "resolvedPath");
        parentRealPath = Objects.requireNonNull(parentRealPath, "parentRealPath");
    }

    public WorkspacePathResult(ResolvedWorkspacePath resolvedPath, boolean exists) {
        this(resolvedPath, exists, Optional.empty());
    }
}
