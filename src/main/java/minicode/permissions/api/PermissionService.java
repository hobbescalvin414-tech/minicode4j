package minicode.permissions.api;

import minicode.permissions.model.*;

import java.nio.file.Path;

public interface PermissionService {
    PermissionGrant ensurePath(Path path, PathIntent intent, PermissionContext context);

    PermissionGrant ensureCommand(CommandSignature signature, CommandClassification classification,
                                  PermissionContext context);

    default PermissionGrant ensureEdit(PermissionResource.EditResource resource, PermissionContext context) {
        throw new UnsupportedOperationException("Edit permission is not implemented by this service");
    }

    default PermissionGrant ensureMcpTool(PermissionResource.McpToolResource resource, PermissionContext context) {
        throw new UnsupportedOperationException("MCP tool permission is not implemented by this service");
    }

    default void beginTurn(String turnId) {
    }

    default void endTurn(String turnId) {
    }
}
