package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.service.PromptingPermissionService;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.tools.validation.ToolInputValidation;
import minicode.workspace.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ListFilesTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int MAX_DEPTH = 10;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2_000;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "list_files",
            "List files and directories under a workspace directory without reading file contents.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.READ),
            ToolStatus.AVAILABLE
    );

    private final PermissionService permissionService;
    private final WorkspacePathResolver workspacePathResolver;

    public ListFilesTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()), new WorkspacePathResolver());
    }

    public ListFilesTool(PermissionService permissionService, WorkspacePathResolver workspacePathResolver) {
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
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
                .pathField("path", false)
                .optionalInteger("depth", 0, MAX_DEPTH)
                .optionalInteger("maxDepth", 0, MAX_DEPTH)
                .optionalInteger("limit", 1, MAX_LIMIT)
                .optionalBoolean("includeHidden")
                .custom((rawInput, builder) -> {
                    if (!builder.normalized().has("maxDepth") && builder.normalized().has("depth")) {
                        builder.normalized().put("maxDepth", builder.normalized().get("depth").asInt());
                    }
                    builder.normalized().remove("depth");
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String inputPath = normalizedInput.has("path") ? normalizedInput.get("path").asText() : ".";
        int maxDepth = normalizedInput.has("maxDepth") ? normalizedInput.get("maxDepth").asInt() : DEFAULT_MAX_DEPTH;
        int limit = normalizedInput.has("limit") ? normalizedInput.get("limit").asInt() : DEFAULT_LIMIT;
        boolean includeHidden = normalizedInput.has("includeHidden") && normalizedInput.get("includeHidden").asBoolean();

        try {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult base = workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.LIST,
                    true,
                    true
            ));
            ensurePathAllowed(base, toolContext);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

            Listing listing = new Listing(limit);
            ListTraversalAuthorization traversalAuthorization = listTraversalAuthorization(base);
            collect(toolContext.cwd(), base.resolvedPath().normalizedPath(), base.resolvedPath().normalizedPath(), 0, maxDepth,
                    includeHidden, listing, toolContext, traversalAuthorization);
            return ToolResult.ok(format(base.resolvedPath().normalizedPath(), listing));
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException exception) {
            return ToolResult.error("Failed to list files: " + exception.getMessage());
        }
    }

    private void ensurePathAllowed(WorkspacePathResult base, ToolContext toolContext) {
        if (base.resolvedPath().boundary() == WorkspaceBoundary.INSIDE_CWD) {
            return;
        }
        PermissionContext permissionContext = new PermissionContext(
                toolContext.sessionId(),
                toolContext.turnId(),
                toolContext.toolUseId()
        );
        toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
        permissionService.ensurePath(base.resolvedPath().normalizedPath(), PathIntent.LIST, permissionContext);
        toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
    }

    private void collect(Path cwd, Path base, Path directory, int depth, int maxDepth, boolean includeHidden,
                         Listing listing, ToolContext toolContext,
                         ListTraversalAuthorization traversalAuthorization) throws IOException {
        toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        if (depth >= maxDepth || listing.truncated()) {
            return;
        }

        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                if (!includeHidden && isHiddenName(child)) {
                    continue;
                }
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(child -> relativeName(base, child)));

        for (Path child : children) {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult resolvedChild = resolveChild(cwd, child);
            if (!traversalAuthorization.covers(resolvedChild)) {
                ensurePathAllowed(resolvedChild, toolContext);
            }
            boolean directoryChild = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
            if (!listing.add(relativeName(base, child) + (directoryChild ? "/" : ""))) {
                return;
            }
            if (directoryChild) {
                collect(cwd, base, child, depth + 1, maxDepth, includeHidden, listing, toolContext,
                        traversalAuthorization);
            }
        }
    }

    private static ListTraversalAuthorization listTraversalAuthorization(WorkspacePathResult base) {
        if (base.resolvedPath().boundary() == WorkspaceBoundary.INSIDE_CWD) {
            return ListTraversalAuthorization.none();
        }
        return new ListTraversalAuthorization(
                Optional.of(base.resolvedPath().normalizedPath()),
                base.resolvedPath().realPath()
        );
    }

    private WorkspacePathResult resolveChild(Path cwd, Path child) {
        try {
            return workspacePathResolver.resolve(new WorkspacePathRequest(
                    cwd,
                    child.toString(),
                    PathIntent.LIST,
                    true,
                    true
            ));
        } catch (WorkspacePathException directoryException) {
            if (directoryException.getMessage() == null
                    || !directoryException.getMessage().startsWith("Expected directory but found file:")) {
                throw directoryException;
            }
            return workspacePathResolver.resolve(new WorkspacePathRequest(
                    cwd,
                    child.toString(),
                    PathIntent.LIST,
                    true,
                    false
            ));
        }
    }

    private static boolean isHiddenName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().startsWith(".");
    }

    private static String relativeName(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }

    private static String format(Path base, Listing listing) {
        StringBuilder builder = new StringBuilder();
        builder.append("BASE: ").append(base).append('\n');
        builder.append("COUNT: ").append(listing.entries().size()).append('\n');
        builder.append("TRUNCATED: ").append(listing.truncated()).append('\n');
        for (String entry : listing.entries()) {
            builder.append(entry).append('\n');
        }
        return builder.toString();
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string")
                .put("description", "Directory path to list. Relative paths are resolved from cwd.");
        ObjectNode depth = properties.putObject("depth");
        depth.put("type", "integer");
        depth.put("minimum", 0);
        depth.put("maximum", MAX_DEPTH);
        depth.put("description", "Alias for maxDepth.");
        ObjectNode maxDepth = properties.putObject("maxDepth");
        maxDepth.put("type", "integer");
        maxDepth.put("minimum", 0);
        maxDepth.put("maximum", MAX_DEPTH);
        maxDepth.put("description", "Maximum directory depth to traverse.");
        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        limit.put("description", "Maximum number of entries to return.");
        properties.putObject("includeHidden").put("type", "boolean")
                .put("description", "Whether to include names starting with a dot.");
        schema.set("required", JSON.arrayNode());
        return schema;
    }

    private static final class Listing {
        private final int limit;
        private final List<String> entries = new ArrayList<>();
        private boolean truncated;

        private Listing(int limit) {
            this.limit = limit;
        }

        private boolean add(String entry) {
            if (entries.size() >= limit) {
                truncated = true;
                return false;
            }
            entries.add(entry);
            return true;
        }

        private List<String> entries() {
            return entries;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private record ListTraversalAuthorization(Optional<Path> normalizedBase, Optional<Path> realBase) {
        private static ListTraversalAuthorization none() {
            return new ListTraversalAuthorization(Optional.empty(), Optional.empty());
        }

        private boolean covers(WorkspacePathResult candidate) {
            if (normalizedBase.isEmpty()) {
                return false;
            }
            if (!candidate.resolvedPath().normalizedPath().startsWith(normalizedBase.orElseThrow())) {
                return false;
            }
            if (realBase.isEmpty()) {
                return true;
            }
            return candidate.resolvedPath().realPath()
                    .map(realPath -> realPath.startsWith(realBase.orElseThrow()))
                    .orElse(true);
        }
    }
}
