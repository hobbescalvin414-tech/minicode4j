package minicode.tools.api;

import minicode.tools.result.ToolResult;

import java.util.Objects;

@FunctionalInterface
public interface ToolExecutor {
    ToolResult execute(ToolCall call, ToolContext toolContext);

    static ToolExecutor unsupported() {
        return (call, toolContext) -> {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(toolContext, "toolContext");
            throw new IllegalArgumentException("Unknown tool: " + call.toolName());
        };
    }
}
