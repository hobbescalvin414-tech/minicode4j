package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.edit.FileWriteResult;
import minicode.edit.FileWriteService;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.tools.validation.ToolInputValidation;
import minicode.workspace.WorkspacePathException;
import minicode.workspace.WorkspacePathPolicy;
import minicode.workspace.WorkspacePathRequest;
import minicode.workspace.WorkspacePathResolver;
import minicode.workspace.WorkspacePathResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class WriteFileTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "write_file",
            "Create or overwrite a UTF-8 text file relative to the current workspace.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.WRITE),
            ToolStatus.AVAILABLE
    );

    private final PermissionService permissionService;
    private final WorkspacePathResolver workspacePathResolver;
    private final FileWriteService fileWriteService;

    public WriteFileTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()), new WorkspacePathResolver());
    }

    public WriteFileTool(PermissionService permissionService, WorkspacePathResolver workspacePathResolver) {
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
        this.fileWriteService = new FileWriteService(permissionService);
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        return ToolInputValidation.object(input)
                .pathField("path", true)
                .custom((rawInput, builder) -> {
                    JsonNode contentNode = rawInput != null && rawInput.isObject() ? rawInput.get("content") : null;
                    if (contentNode == null || contentNode.isNull()) {
                        builder.addError("content must exist and be a string");
                        return;
                    }
                    if (!contentNode.isTextual()) {
                        builder.addError("content must be a string");
                        return;
                    }
                    builder.normalized().put("content", contentNode.asText());
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String inputPath = normalizedInput.get("path").asText();
        String content = normalizedInput.get("content").asText();

        try {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult resolvedPath = workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.WRITE,
                    WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT
            ));
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            Path targetPath = resolvedPath.resolvedPath().normalizedPath();
            PermissionContext permissionContext = new PermissionContext(
                    toolContext.sessionId(),
                    toolContext.turnId(),
                    toolContext.toolUseId()
            );
            FileWriteResult result = fileWriteService.apply(
                    targetPath,
                    inputPath,
                    content,
                    toolContext.toolUseId(),
                    permissionContext,
                    () -> toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT)
            );
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            return ToolResult.ok(result.noOp()
                    ? result.message()
                    : formatSuccess(targetPath, result.operation().orElseThrow(), content.length()));
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to write file " + inputPath + ": " + exception.getMessage());
        }
    }

    private static String formatSuccess(Path targetPath, PermissionResource.EditOperation operation, int contentChars) {
        return String.join("\n",
                "WROTE: " + displayPath(targetPath),
                "OPERATION: " + operation.name(),
                "CHARS: " + contentChars
        );
    }

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "File path to create or overwrite. Relative paths are resolved from cwd.");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "UTF-8 content to write. Empty strings are allowed.");

        ArrayNode required = schema.putArray("required");
        required.add("path");
        required.add("content");

        return schema;
    }
}
