package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.util.Objects;
import java.util.Optional;

public record FileWriteResult(boolean noOp, Optional<PermissionResource.EditOperation> operation, String message) {
    public FileWriteResult {
        operation = Objects.requireNonNull(operation, "operation");
        if (noOp && operation.isPresent()) {
            throw new IllegalArgumentException("no-op write result cannot carry an operation");
        }
        if (!noOp && operation.isEmpty()) {
            throw new IllegalArgumentException("applied write result must carry an operation");
        }
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public static FileWriteResult noOp(String message) {
        return new FileWriteResult(true, Optional.empty(), message);
    }

    public static FileWriteResult applied(PermissionResource.EditOperation operation, String message) {
        return new FileWriteResult(false, Optional.of(Objects.requireNonNull(operation, "operation")), message);
    }
}
