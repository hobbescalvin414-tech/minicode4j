package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
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
import minicode.workspace.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GrepFilesTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int DEFAULT_MAX_MATCHES = 100;
    private static final int MAX_MATCHES = 1_000;
    private static final int BINARY_PROBE_BYTES = 8_192;
    private static final int PREVIEW_CHARS = 240;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "grep_files",
            "Search UTF-8 text files in the workspace without invoking shell grep.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.READ),
            ToolStatus.AVAILABLE
    );

    private final PermissionService permissionService;
    private final WorkspacePathResolver workspacePathResolver;

    public GrepFilesTool() {
        this(new PromptingPermissionService(PermissionPromptHandler.unavailable()), new WorkspacePathResolver());
    }

    public GrepFilesTool(PermissionService permissionService, WorkspacePathResolver workspacePathResolver) {
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
                .optionalString("pattern")
                .optionalString("query")
                .optionalBoolean("regex")
                .optionalBoolean("caseSensitive")
                .optionalInteger("limit", 1, MAX_MATCHES)
                .optionalInteger("maxMatches", 1, MAX_MATCHES)
                .optionalBoolean("includeHidden")
                .custom((rawInput, builder) -> {
                    boolean hasQuery = builder.normalized().has("query");
                    boolean hasPattern = builder.normalized().has("pattern");
                    if (!hasQuery && !hasPattern) {
                        builder.addError("pattern or query must exist and be a string");
                        return;
                    }
                    if (!hasQuery && hasPattern) {
                        builder.normalized().put("query", builder.normalized().get("pattern").asText());
                    }
                    if (!builder.normalized().has("maxMatches") && builder.normalized().has("limit")) {
                        builder.normalized().put("maxMatches", builder.normalized().get("limit").asInt());
                    }
                    builder.normalized().remove("limit");
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String inputPath = normalizedInput.has("path") ? normalizedInput.get("path").asText() : ".";
        String query = normalizedInput.get("query").asText();
        boolean regex = normalizedInput.has("regex") && normalizedInput.get("regex").asBoolean();
        boolean caseSensitive = normalizedInput.has("caseSensitive")
                && normalizedInput.get("caseSensitive").asBoolean();
        int maxMatches = normalizedInput.has("maxMatches")
                ? normalizedInput.get("maxMatches").asInt()
                : DEFAULT_MAX_MATCHES;
        boolean includeHidden = normalizedInput.has("includeHidden") && normalizedInput.get("includeHidden").asBoolean();

        Pattern pattern;
        try {
            pattern = regex ? compilePattern(query, caseSensitive) : null;
        } catch (PatternSyntaxException exception) {
            return ToolResult.error("Invalid regex: " + exception.getDescription());
        }

        try {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult base = resolveFileOrDirectory(toolContext, inputPath);
            ensurePathAllowed(base, toolContext);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

            SearchResult result = new SearchResult(maxMatches);
            if (Files.isDirectory(base.resolvedPath().normalizedPath())) {
                searchDirectory(toolContext.cwd(), base.resolvedPath().normalizedPath(), base.resolvedPath().normalizedPath(), query,
                        pattern, regex, caseSensitive, includeHidden, result, toolContext);
            } else if (includeHidden || !hasHiddenSegment(base.resolvedPath().normalizedPath().getFileName())) {
                searchFile(outputBaseForFile(toolContext.cwd(), base.resolvedPath().normalizedPath()),
                        base.resolvedPath().normalizedPath(),
                        query, pattern, regex, caseSensitive, result, toolContext);
            }
            return ToolResult.ok(format(base.resolvedPath().normalizedPath(), query, regex, result));
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (WorkspacePathException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (IOException | UncheckedIOException exception) {
            return ToolResult.error("Failed to search files: " + exception.getMessage());
        }
    }

    private WorkspacePathResult resolveFileOrDirectory(ToolContext toolContext, String inputPath) {
        try {
            return workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.SEARCH,
                    true,
                    true
            ));
        } catch (WorkspacePathException directoryException) {
            if (directoryException.getMessage() == null
                    || !directoryException.getMessage().startsWith("Expected directory but found file:")) {
                throw directoryException;
            }
            return workspacePathResolver.resolve(new WorkspacePathRequest(
                    toolContext.cwd(),
                    inputPath,
                    PathIntent.SEARCH,
                    true,
                    false
            ));
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
        permissionService.ensurePath(base.resolvedPath().normalizedPath(), PathIntent.SEARCH, permissionContext);
        toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
    }

    private static Pattern compilePattern(String query, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(query, flags);
    }

    private void searchDirectory(Path cwd, Path base, Path directory, String query, Pattern pattern, boolean regex,
                                 boolean caseSensitive, boolean includeHidden, SearchResult result,
                                 ToolContext toolContext) throws IOException {
        toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        if (result.truncated()) {
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
        children.sort(Comparator.comparing(child -> base.relativize(child).toString()));
        for (Path child : children) {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            WorkspacePathResult resolvedChild = resolveChild(cwd, child);
            ensurePathAllowed(resolvedChild, toolContext);
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                searchDirectory(cwd, base, child, query, pattern, regex, caseSensitive, includeHidden, result, toolContext);
            } else {
                searchFile(base, child, query, pattern, regex, caseSensitive, result, toolContext);
            }
            if (result.truncated()) {
                return;
            }
        }
    }

    private WorkspacePathResult resolveChild(Path cwd, Path child) {
        try {
            return workspacePathResolver.resolve(new WorkspacePathRequest(
                    cwd,
                    child.toString(),
                    PathIntent.SEARCH,
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
                    PathIntent.SEARCH,
                    true,
                    false
            ));
        }
    }

    private static void searchFile(Path base, Path file, String query, Pattern pattern, boolean regex,
                                   boolean caseSensitive, SearchResult result, ToolContext toolContext)
            throws IOException {
        toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        if (result.truncated() || isBinary(file)) {
            return;
        }
        result.incrementScannedFiles();

        String plainNeedle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                lineNumber++;
                boolean matched = regex
                        ? pattern.matcher(line).find()
                        : containsPlain(line, plainNeedle, caseSensitive);
                if (matched && !result.add(relativeName(base, file) + ":" + lineNumber + ": " + preview(line))) {
                    return;
                }
            }
        } catch (MalformedInputException exception) {
            // Treat invalid UTF-8 like binary content for this first implementation.
        }
    }

    private static boolean containsPlain(String line, String plainNeedle, boolean caseSensitive) {
        String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
        return haystack.contains(plainNeedle);
    }

    private static boolean isBinary(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = input.readNBytes(BINARY_PROBE_BYTES);
            for (byte value : buffer) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Path outputBaseForFile(Path cwd, Path file) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        return file.startsWith(normalizedCwd) ? normalizedCwd : file.getParent();
    }

    private static boolean isHiddenName(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().startsWith(".");
    }

    private static boolean hasHiddenSegment(Path path) {
        return path != null && path.toString().startsWith(".");
    }

    private static String relativeName(Path base, Path file) {
        if (base == null) {
            return file.getFileName().toString();
        }
        return base.relativize(file).toString().replace('\\', '/');
    }

    private static String preview(String line) {
        String singleLine = line.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        if (singleLine.length() <= PREVIEW_CHARS) {
            return singleLine;
        }
        return singleLine.substring(0, PREVIEW_CHARS);
    }

    private static String format(Path base, String query, boolean regex, SearchResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("BASE: ").append(base).append('\n');
        builder.append("QUERY: ").append(preview(query)).append('\n');
        builder.append("REGEX: ").append(regex).append('\n');
        builder.append("MATCHES: ").append(result.matches().size()).append('\n');
        builder.append("SCANNED_FILES: ").append(result.scannedFiles()).append('\n');
        builder.append("TRUNCATED: ").append(result.truncated()).append('\n');
        for (String match : result.matches()) {
            builder.append(match).append('\n');
        }
        return builder.toString();
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string")
                .put("description", "File or directory path to search. Relative paths are resolved from cwd.");
        properties.putObject("pattern").put("type", "string")
                .put("description", "Text or regular expression to search for. Preferred Stage 4 field.");
        properties.putObject("query").put("type", "string")
                .put("description", "Alias for pattern.");
        properties.putObject("regex").put("type", "boolean")
                .put("description", "Treat query as a Java regular expression.");
        properties.putObject("caseSensitive").put("type", "boolean")
                .put("description", "Use case-sensitive matching.");
        ObjectNode maxMatches = properties.putObject("maxMatches");
        maxMatches.put("type", "integer");
        maxMatches.put("minimum", 1);
        maxMatches.put("maximum", MAX_MATCHES);
        maxMatches.put("description", "Maximum number of matches to return.");
        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_MATCHES);
        limit.put("description", "Alias for maxMatches.");
        properties.putObject("includeHidden").put("type", "boolean")
                .put("description", "Whether to include names starting with a dot.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    private static final class SearchResult {
        private final int maxMatches;
        private final List<String> matches = new ArrayList<>();
        private int scannedFiles;
        private boolean truncated;

        private SearchResult(int maxMatches) {
            this.maxMatches = maxMatches;
        }

        private boolean add(String match) {
            if (matches.size() >= maxMatches) {
                truncated = true;
                return false;
            }
            matches.add(match);
            return true;
        }

        private List<String> matches() {
            return matches;
        }

        private void incrementScannedFiles() {
            scannedFiles++;
        }

        private int scannedFiles() {
            return scannedFiles;
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
