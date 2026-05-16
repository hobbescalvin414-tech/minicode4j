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
import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDeniedException;
import minicode.permissions.model.PermissionResource;
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
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class ModifyFileTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "modify_file",
            "Create or replace a UTF-8 text file with reviewed full-file content.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.WRITE),
            ToolStatus.AVAILABLE
    );

    private final WorkspacePathResolver workspacePathResolver;
    private final FileWriteService fileWriteService;

    public ModifyFileTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()), new WorkspacePathResolver());
    }

    public ModifyFileTool(PermissionService permissionService, WorkspacePathResolver workspacePathResolver) {
        this.workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
        this.fileWriteService = new FileWriteService(Objects.requireNonNull(permissionService, "permissionService"));
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
                    JsonNode content = rawInput != null && rawInput.isObject() ? rawInput.get("content") : null;
                    if (content == null || content.isNull()) {
                        builder.addError("content must exist and be a string");
                    } else if (!content.isTextual()) {
                        builder.addError("content must be a string");
                    } else {
                        builder.normalized().put("content", content.asText());
                    }
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
            Path targetPath = resolvedPath.resolvedPath().normalizedPath();
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            PermissionContext permissionContext = new PermissionContext(
                    toolContext.sessionId(),
                    toolContext.turnId(),
                    toolContext.toolUseId()
            );
            FileWriteResult result = fileWriteService.applyReviewedReplacement(
                    targetPath,
                    inputPath,
                    PermissionResource.EditOperation.MODIFY,
                    "Modify file " + inputPath,
                    content,
                    toolContext.toolUseId(),
                    permissionContext,
                    () -> toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT)
            );
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            return ToolResult.ok(result.noOp()
                    ? result.message()
                    : "MODIFIED: " + displayPath(targetPath));
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to modify file " + inputPath + ": " + exception.getMessage());
        }
    }

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "File path to create or replace. Relative paths are resolved from cwd.");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "Complete replacement file content. Empty strings are allowed.");

        ArrayNode required = schema.putArray("required");
        required.add("path");
        required.add("content");
        return schema;
    }
}
