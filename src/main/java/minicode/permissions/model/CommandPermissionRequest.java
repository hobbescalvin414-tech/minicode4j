package minicode.permissions.model;

import java.util.Objects;
import java.util.Optional;

public record CommandPermissionRequest(String requestId, CommandSignature signature,
                                       CommandClassification classification, String cwd,
                                       Optional<String> toolUseId) {
    public CommandPermissionRequest {
        if (Objects.requireNonNull(requestId, "requestId").isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        signature = Objects.requireNonNull(signature, "signature");
        classification = Objects.requireNonNull(classification, "classification");
        if (Objects.requireNonNull(cwd, "cwd").isBlank()) {
            throw new IllegalArgumentException("cwd must not be blank");
        }
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
    }
}
