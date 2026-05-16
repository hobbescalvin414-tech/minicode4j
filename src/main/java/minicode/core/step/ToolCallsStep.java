package minicode.core.step;

import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;
import minicode.tools.api.ToolCall;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ToolCallsStep(List<ToolCall> calls, Optional<String> content, ContentKind contentKind,
                            List<ProviderThinkingBlock> thinkingBlocks, Optional<StepDiagnostics> diagnostics,
                            Optional<ProviderUsage> usage) implements AgentStep {
    public ToolCallsStep {
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("tool calls step requires at least one call");
        }
        content = Objects.requireNonNull(content, "content");
        contentKind = Objects.requireNonNull(contentKind, "contentKind");
        thinkingBlocks = List.copyOf(Objects.requireNonNull(thinkingBlocks, "thinkingBlocks"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        usage = Objects.requireNonNull(usage, "usage");
    }
}
