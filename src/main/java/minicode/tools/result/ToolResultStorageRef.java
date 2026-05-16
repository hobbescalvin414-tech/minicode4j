package minicode.tools.result;

import java.nio.file.Path;
import java.util.Objects;

public record ToolResultStorageRef(String id, Path path, long bytes) {
    public ToolResultStorageRef {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        path = Objects.requireNonNull(path, "path");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
    }
}
