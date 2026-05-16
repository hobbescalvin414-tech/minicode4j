package minicode.permissions.store;

import minicode.permissions.model.PermissionResource;

import java.nio.file.Path;
import java.util.Objects;

public record PermissionResourceKey(String type, String fingerprint) {
    public PermissionResourceKey {
        if (Objects.requireNonNull(type, "type").isBlank()) {
            throw new IllegalArgumentException("resource key type must not be blank");
        }
        if (Objects.requireNonNull(fingerprint, "fingerprint").isBlank()) {
            throw new IllegalArgumentException("resource key fingerprint must not be blank");
        }
    }

    public static PermissionResourceKey from(PermissionResource resource) {
        return switch (Objects.requireNonNull(resource, "resource")) {
            case PermissionResource.PathResource pathResource -> new PermissionResourceKey(
                    "path",
                    pathResource.intent() + "|" + normalizedPath(pathResource.path())
            );
            case PermissionResource.CommandResource commandResource -> new PermissionResourceKey(
                    "command",
                    commandResource.classification() + "|" + commandResource.signature().executable()
                            + "|" + String.join("\u001f", commandResource.signature().arguments())
            );
            case PermissionResource.EditResource editResource -> new PermissionResourceKey(
                    "edit",
                    editResource.operation() + "|" + normalizedPath(editResource.path())
                            + "|" + editResource.reviewFingerprint()
            );
            case PermissionResource.McpToolResource mcpToolResource -> new PermissionResourceKey(
                    "mcp_tool",
                    mcpToolResource.serverName() + "|" + mcpToolResource.toolName()
                            + "|" + mcpToolResource.wrappedName()
            );
        };
    }

    private static String normalizedPath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
