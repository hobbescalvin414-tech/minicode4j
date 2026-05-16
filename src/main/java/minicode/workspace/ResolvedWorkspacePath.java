package minicode.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ResolvedWorkspacePath(String rawPath,
                                    Path normalizedPath,
                                    Optional<Path> realPath,
                                    WorkspaceBoundary boundary) {
    public ResolvedWorkspacePath {
        if (Objects.requireNonNull(rawPath, "rawPath").isBlank()) {
            throw new IllegalArgumentException("rawPath must not be blank");
        }
        normalizedPath = Objects.requireNonNull(normalizedPath, "normalizedPath");
        realPath = Objects.requireNonNull(realPath, "realPath");
        boundary = Objects.requireNonNull(boundary, "boundary");
    }
}
