package minicode.tools.result;

import minicode.core.message.ToolResultMessage;

import java.util.List;
import java.util.Objects;

public record ToolResultBudgetResult(List<ToolResultMessage> results, List<ToolResultReplacementRecord> replacements) {
    public ToolResultBudgetResult {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        replacements = List.copyOf(Objects.requireNonNull(replacements, "replacements"));
    }
}
