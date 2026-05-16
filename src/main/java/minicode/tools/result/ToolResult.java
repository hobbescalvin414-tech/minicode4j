package minicode.tools.result;

import java.util.Objects;
import java.util.Optional;

public record ToolResult(String content, boolean error, boolean awaitUser,
                         Optional<BackgroundTaskResult> backgroundTask) {
    public ToolResult {
        content = Objects.requireNonNull(content, "content");
        backgroundTask = Objects.requireNonNull(backgroundTask, "backgroundTask");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(content, false, false, Optional.empty());
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true, false, Optional.empty());
    }
}
