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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class EditFileTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "edit_file",
            "Edit an existing UTF-8 text file by exact text replacement. By default replaces the first oldText occurrence; set replaceAll=true to replace every occurrence.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.WRITE),
            ToolStatus.AVAILABLE
    );

    private final WorkspacePathResolver workspacePathResolver;
    private final FileWriteService fileWriteService;

    public EditFileTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()), new WorkspacePathResolver());
    }

    public EditFileTool(PermissionService permissionService, WorkspacePathResolver workspacePathResolver) {
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
                    JsonNode oldText = rawInput != null && rawInput.isObject() ? rawInput.get("oldText") : null;
                    if (oldText == null || oldText.isNull()) {
                        builder.addError("oldText must exist and be a non-empty string");
                    } else if (!oldText.isTextual()) {
                        builder.addError("oldText must be a string");
                    } else if (oldText.asText().isEmpty()) {
                        builder.addError("oldText must be a non-empty string");
                    } else {
                        builder.normalized().put("oldText", oldText.asText());
                    }

                    JsonNode newText = rawInput != null && rawInput.isObject() ? rawInput.get("newText") : null;
                    if (newText == null || newText.isNull()) {
                        builder.addError("newText must exist and be a string");
                    } else if (!newText.isTextual()) {
                        builder.addError("newText must be a string");
                    } else {
                        builder.normalized().put("newText", newText.asText());
                    }

                    JsonNode replaceAll = rawInput != null && rawInput.isObject() ? rawInput.get("replaceAll") : null;
                    if (replaceAll == null || replaceAll.isNull()) {
                        builder.normalized().put("replaceAll", false);
                    } else if (!replaceAll.isBoolean()) {
                        builder.addError("replaceAll must be a boolean; omit it to replace only the first oldText occurrence");
                    } else {
                        builder.normalized().put("replaceAll", replaceAll.asBoolean());
                    }
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String inputPath = normalizedInput.get("path").asText();
        String oldText = normalizedInput.get("oldText").asText();
        String newText = normalizedInput.get("newText").asText();
        boolean replaceAll = normalizedInput.get("replaceAll").asBoolean();

        try {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult resolvedPath = workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.WRITE,
                    WorkspacePathPolicy.EXISTING_FILE
            ));
            Path targetPath = resolvedPath.resolvedPath().normalizedPath();
            String original = Files.readString(targetPath, StandardCharsets.UTF_8);

            int first = original.indexOf(oldText);
            if (first < 0) {
                return ToolResult.error("oldText not found in " + inputPath
                        + ". edit_file uses exact text matching; provide a longer exact oldText copied from the file.");
            }

            String nextContent = replaceAll
                    ? original.replace(oldText, newText)
                    : original.substring(0, first) + newText + original.substring(first + oldText.length());
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            PermissionContext permissionContext = new PermissionContext(
                    toolContext.sessionId(),
                    toolContext.turnId(),
                    toolContext.toolUseId()
            );
            FileWriteResult result = fileWriteService.applyReviewedReplacement(
                    targetPath,
                    inputPath,
                    PermissionResource.EditOperation.EDIT,
                    "Replace text in " + inputPath,
                    nextContent,
                    toolContext.toolUseId(),
                    permissionContext,
                    () -> toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT)
            );
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            return ToolResult.ok(result.noOp()
                    ? result.message()
                    : "EDITED: " + displayPath(targetPath));
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to edit file " + inputPath + ": " + exception.getMessage());
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
                .put("description", "Existing file path. Relative paths are resolved from cwd.");
        properties.putObject("oldText")
                .put("type", "string")
                .put("description", "Exact non-empty text to replace. Provide enough surrounding context to avoid unintended first-match edits.");
        properties.putObject("newText")
                .put("type", "string")
                .put("description", "Replacement text. Empty strings are allowed.");
        properties.putObject("replaceAll")
                .put("type", "boolean")
                .put("description", "When false or omitted, replace only the first oldText occurrence. When true, replace every exact occurrence.");

        ArrayNode required = schema.putArray("required");
        required.add("path");
        required.add("oldText");
        required.add("newText");
        return schema;
    }
}
