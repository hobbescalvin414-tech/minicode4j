package minicode.tools.result;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class ToolResultStorage {
    private final Path root;

    public ToolResultStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public ToolResultStorageRef store(String content) {
        String actualContent = Objects.requireNonNull(content, "content");
        String id = UUID.randomUUID().toString();
        Path path = root.resolve(id + ".txt");
        try {
            Files.createDirectories(root);
            byte[] bytes = actualContent.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            return new ToolResultStorageRef(id, path, bytes.length);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
