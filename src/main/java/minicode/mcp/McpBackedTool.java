package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDeniedException;
import minicode.permissions.model.PermissionResource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class McpBackedTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final McpToolDescriptor descriptor;
    private final McpClient client;
    private final Optional<PermissionService> permissionService;
    private final JsonNode inputSchema;
    private final ToolMetadata metadata;

    public McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client) {
        this(serverName, descriptor, client, Optional.empty());
    }

    public McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client,
                         PermissionService permissionService) {
        this(serverName, descriptor, client, Optional.of(permissionService));
    }

    private McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client,
                          Optional<PermissionService> permissionService) {
        this.serverName = requireText(serverName, "serverName");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.client = Objects.requireNonNull(client, "client");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.inputSchema = normalizeInputSchema(descriptor.inputSchema().orElse(null));
        this.metadata = new ToolMetadata(
                McpToolName.wrappedName(serverName, descriptor.name()),
                descriptor.description().isBlank()
                        ? "Call MCP tool " + descriptor.name() + " from server " + serverName + "."
                        : descriptor.description(),
                inputSchema,
                ToolOrigin.MCP,
                Set.of(ToolCapability.COMMAND),
                ToolStatus.AVAILABLE
        );
    }

    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return ValidationResult.valid(MAPPER.createObjectNode());
        }
        if (!input.isObject()) {
            return ValidationResult.invalid(List.of("MCP tool input must be a JSON object"));
        }
        return ValidationResult.valid(input);
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        if (permissionService.isPresent()) {
            try {
                permissionService.orElseThrow().ensureMcpTool(new PermissionResource.McpToolResource(
                        serverName,
                        descriptor.name(),
                        metadata.name(),
                        metadata.description()
                ), new PermissionContext(toolContext.sessionId(), toolContext.turnId(), toolContext.toolUseId()));
            } catch (PermissionDeniedException exception) {
                String message = exception.feedback()
                        .map(feedback -> "Permission denied: " + feedback)
                        .orElse("Permission denied");
                return ToolResult.error(message);
            }
        }
        return McpToolResultFormatter.toToolResult(client.callTool(descriptor.name(), normalizedInput));
    }

    public String serverName() {
        return serverName;
    }

    public String originalToolName() {
        return descriptor.name();
    }

    private static JsonNode normalizeInputSchema(JsonNode schema) {
        if (schema != null && schema.isObject()) {
            return schema;
        }
        ObjectNode fallback = MAPPER.createObjectNode();
        fallback.put("type", "object");
        fallback.put("additionalProperties", true);
        return fallback;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
