package minicode.workspace;

import minicode.permissions.model.PathIntent;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspacePathRequest(Path cwd,
                                   String rawPath,
                                   PathIntent intent,
                                   boolean mustExist,
                                   boolean allowDirectory,
                                   WorkspacePathPolicy policy) {
    public WorkspacePathRequest {
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(rawPath, "rawPath").isBlank()) {
            throw new IllegalArgumentException("rawPath must not be blank");
        }
        intent = Objects.requireNonNull(intent, "intent");
        policy = Objects.requireNonNull(policy, "policy");
    }

    public WorkspacePathRequest(Path cwd, String rawPath, PathIntent intent,
                                boolean mustExist, boolean allowDirectory) {
        this(cwd, rawPath, intent, mustExist, allowDirectory, policyFrom(mustExist, allowDirectory));
    }

    public WorkspacePathRequest(Path cwd, String rawPath, PathIntent intent, WorkspacePathPolicy policy) {
        this(cwd, rawPath, intent, policy.mustExist(), policy.allowDirectory(), policy);
    }

    private static WorkspacePathPolicy policyFrom(boolean mustExist, boolean allowDirectory) {
        if (!mustExist) {
            return WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT;
        }
        return allowDirectory ? WorkspacePathPolicy.EXISTING_DIRECTORY : WorkspacePathPolicy.EXISTING_FILE;
    }
}
