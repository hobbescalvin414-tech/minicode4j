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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PatchFileTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "patch_file",
            "Apply multiple exact-text replacements to one existing UTF-8 text file in one reviewed operation. Each replacement defaults to replaceOnce; set replaceAll=true to replace every exact occurrence.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.WRITE),
            ToolStatus.AVAILABLE
    );

    private final WorkspacePathResolver workspacePathResolver;
    private final FileWriteService fileWriteService;

    public PatchFileTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()), new WorkspacePathResolver());
    }

    public PatchFileTool(PermissionService permissionService, WorkspacePathResolver workspacePathResolver) {
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
                    JsonNode replacements = rawInput != null && rawInput.isObject() ? rawInput.get("replacements") : null;
                    if (replacements == null || replacements.isNull()) {
                        builder.addError("replacements must exist and be a non-empty array");
                        return;
                    }
                    if (!replacements.isArray()) {
                        builder.addError("replacements must be an array");
                        return;
                    }
                    if (replacements.isEmpty()) {
                        builder.addError("replacements must be a non-empty array");
                        return;
                    }

                    ArrayNode normalizedReplacements = builder.normalized().putArray("replacements");
                    int index = 0;
                    for (JsonNode replacement : replacements) {
                        index++;
                        if (!replacement.isObject()) {
                            builder.addError("replacements[" + index + "] must be an object");
                            continue;
                        }
                        JsonNode search = replacement.get("search");
                        JsonNode replace = replacement.get("replace");
                        JsonNode replaceAll = replacement.get("replaceAll");
                        ObjectNode normalizedReplacement = JSON.objectNode();
                        if (search == null || search.isNull()) {
                            builder.addError("replacements[" + index + "].search must exist and be a non-empty string");
                        } else if (!search.isTextual()) {
                            builder.addError("replacements[" + index + "].search must be a string");
                        } else if (search.asText().isEmpty()) {
                            builder.addError("replacements[" + index + "].search must be a non-empty string");
                        } else {
                            normalizedReplacement.put("search", search.asText());
                        }

                        if (replace == null || replace.isNull()) {
                            builder.addError("replacements[" + index + "].replace must exist and be a string");
                        } else if (!replace.isTextual()) {
                            builder.addError("replacements[" + index + "].replace must be a string");
                        } else {
                            normalizedReplacement.put("replace", replace.asText());
                        }

                        if (replaceAll == null || replaceAll.isNull()) {
                            normalizedReplacement.put("replaceAll", false);
                        } else if (!replaceAll.isBoolean()) {
                            builder.addError("replacements[" + index + "].replaceAll must be a boolean; omit it to replace only the first exact occurrence");
                        } else {
                            normalizedReplacement.put("replaceAll", replaceAll.asBoolean());
                        }
                        normalizedReplacements.add(normalizedReplacement);
                    }
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String inputPath = normalizedInput.get("path").asText();

        try {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult resolvedPath = workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.WRITE,
                    WorkspacePathPolicy.EXISTING_FILE
            ));
            Path targetPath = resolvedPath.resolvedPath().normalizedPath();
            String content = Files.readString(targetPath, StandardCharsets.UTF_8);
            List<String> applied = new ArrayList<>();

            ArrayNode replacements = (ArrayNode) normalizedInput.get("replacements");
            for (int i = 0; i < replacements.size(); i++) {
                JsonNode replacement = replacements.get(i);
                String search = replacement.get("search").asText();
                String replace = replacement.get("replace").asText();
                boolean replaceAll = replacement.get("replaceAll").asBoolean();
                if (!content.contains(search)) {
                    return ToolResult.error("Replacement " + (i + 1) + " search text not found in " + inputPath
                            + ". patch_file uses exact text matching; provide a longer exact search copied from the file.");
                }
                content = replaceAll
                        ? content.replace(search, replace)
                        : replaceFirstLiteral(content, search, replace);
                applied.add("#" + (i + 1) + (replaceAll ? " replaceAll" : " replaceOnce"));
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            }

            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            PermissionContext permissionContext = new PermissionContext(
                    toolContext.sessionId(),
                    toolContext.turnId(),
                    toolContext.toolUseId()
            );
            FileWriteResult result = fileWriteService.applyReviewedReplacement(
                    targetPath,
                    inputPath,
                    PermissionResource.EditOperation.PATCH,
                    "Patch file " + inputPath + " with " + applied.size() + " replacement(s)",
                    content,
                    toolContext.toolUseId(),
                    permissionContext,
                    () -> toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT)
            );
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            return ToolResult.ok(result.noOp()
                    ? result.message()
                    : "Patched " + inputPath + " with " + applied.size() + " replacement(s): "
                    + String.join(", ", applied));
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to patch file " + inputPath + ": " + exception.getMessage());
        }
    }

    private static String replaceFirstLiteral(String content, String search, String replace) {
        int index = content.indexOf(search);
        if (index < 0) {
            return content;
        }
        return content.substring(0, index) + replace + content.substring(index + search.length());
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "Existing file path. Relative paths are resolved from cwd.");
        ObjectNode replacements = properties.putObject("replacements");
        replacements.put("type", "array");
        ObjectNode item = replacements.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("search").put("type", "string")
                .put("description", "Exact non-empty text to replace. Provide enough surrounding context to avoid unintended first-match edits.");
        itemProperties.putObject("replace").put("type", "string")
                .put("description", "Replacement text. Empty strings are allowed.");
        itemProperties.putObject("replaceAll").put("type", "boolean")
                .put("description", "When false or omitted, replace only the first exact occurrence. When true, replace every exact occurrence.");
        ArrayNode itemRequired = item.putArray("required");
        itemRequired.add("search");
        itemRequired.add("replace");

        ArrayNode required = schema.putArray("required");
        required.add("path");
        required.add("replacements");
        return schema;
    }
}
