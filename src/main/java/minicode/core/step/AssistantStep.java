package minicode.core.step;

import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AssistantStep(String content, AssistantKind kind, List<ProviderThinkingBlock> thinkingBlocks,
                            Optional<StepDiagnostics> diagnostics, Optional<ProviderUsage> usage) implements AgentStep {
    public AssistantStep {
        content = Objects.requireNonNull(content, "content");
        kind = Objects.requireNonNull(kind, "kind");
        thinkingBlocks = List.copyOf(Objects.requireNonNull(thinkingBlocks, "thinkingBlocks"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        usage = Objects.requireNonNull(usage, "usage");
    }

    public AssistantStep(String content, AssistantKind kind) {
        this(content, kind, List.of(), Optional.empty(), Optional.empty());
    }
}
