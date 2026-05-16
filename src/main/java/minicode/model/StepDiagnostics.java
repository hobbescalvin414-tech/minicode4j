package minicode.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record StepDiagnostics(Optional<String> stopReason, List<String> blockTypes, List<String> ignoredBlockTypes) {
    public StepDiagnostics {
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
        blockTypes = List.copyOf(Objects.requireNonNull(blockTypes, "blockTypes"));
        ignoredBlockTypes = List.copyOf(Objects.requireNonNull(ignoredBlockTypes, "ignoredBlockTypes"));
    }

    public static StepDiagnostics empty() {
        return new StepDiagnostics(Optional.empty(), List.of(), List.of());
    }
}
