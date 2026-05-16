package minicode.mcp;

import minicode.permissions.api.PermissionService;
import minicode.tools.api.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;

public final class McpToolHydrator {
    private McpToolHydrator() {
    }

    public static McpRuntime hydrate(Map<String, McpServerConfig> configs) {
        return hydrate(configs, null);
    }

    public static McpRuntime hydrate(Map<String, McpServerConfig> configs, PermissionService permissionService) {
        return hydrate(configs, permissionService, Path.of(".").toAbsolutePath().normalize());
    }

    public static McpRuntime hydrate(Map<String, McpServerConfig> configs, PermissionService permissionService,
                                     Path baseCwd) {
        List<Tool> tools = new ArrayList<>();
        List<McpServerSummary> summaries = new ArrayList<>();
        List<McpClient> clients = new ArrayList<>();
        Path actualBaseCwd = Objects.requireNonNull(baseCwd, "baseCwd").toAbsolutePath().normalize();
        for (Map.Entry<String, McpServerConfig> entry : Objects.requireNonNull(configs, "configs").entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig config = entry.getValue();
            String command = summarizeCommand(config);
            if (!config.enabled()) {
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.DISABLED, 0, Optional.empty()));
                continue;
            }
            StdioMcpClient client = new StdioMcpClient(serverName, config, actualBaseCwd);
            try {
                client.start();
                List<McpToolDescriptor> descriptors = client.listTools();
                for (McpToolDescriptor descriptor : descriptors) {
                    tools.add(permissionService == null
                            ? new McpBackedTool(serverName, descriptor, client)
                            : new McpBackedTool(serverName, descriptor, client, permissionService));
                }
                clients.add(client);
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.CONNECTED,
                        descriptors.size(), Optional.empty()));
            } catch (RuntimeException exception) {
                client.close();
                McpErrorKind kind = exception instanceof McpException mcpException
                        ? mcpException.kind()
                        : McpErrorKind.TOOL_CALL_FAILED;
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.ERROR, 0,
                        Optional.of(messageOrDefault(exception)), Optional.of(kind)));
            }
        }
        return new McpRuntime(tools, summaries, clients);
    }

    private static String summarizeCommand(McpServerConfig config) {
        return config.endpointSummary();
    }

    private static String messageOrDefault(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
