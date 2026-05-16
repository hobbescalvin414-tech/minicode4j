package minicode.tools.builtin;

import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.ToolContext;
import minicode.workspace.ResolvedWorkspacePath;
import minicode.workspace.WorkspaceBoundary;

import java.nio.file.Path;
import java.util.Objects;

@FunctionalInterface
public interface ReadFilePathAccess {
    void ensureReadAllowed(ToolContext toolContext, ResolvedWorkspacePath resolvedPath);

    static ReadFilePathAccess unavailable() {
        return fromPermissionService(new PromptingPermissionService(PermissionPromptHandler.unavailable()));
    }

    static ReadFilePathAccess fromPermissionService(PermissionService permissionService) {
        PermissionService actualPermissionService = Objects.requireNonNull(permissionService, "permissionService");
        return (toolContext, resolvedPath) -> {
            if (resolvedPath.boundary() == WorkspaceBoundary.INSIDE_CWD) {
                return;
            }
            actualPermissionService.ensurePath(
                    resolvedPath.normalizedPath(),
                    PathIntent.READ,
                    new PermissionContext(toolContext.sessionId(), toolContext.turnId(), toolContext.toolUseId())
            );
        };
    }
}
