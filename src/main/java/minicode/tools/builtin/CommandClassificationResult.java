package minicode.tools.builtin;

import minicode.permissions.model.CommandClassification;

import java.util.Objects;

public record CommandClassificationResult(CommandClassification classification, boolean shellSnippet, String reason) {
    public CommandClassificationResult {
        classification = Objects.requireNonNull(classification, "classification");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
