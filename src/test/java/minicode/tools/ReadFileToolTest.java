package minicode.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.builtin.ReadFilePathAccess;
import minicode.tools.builtin.ReadFileTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReadFileToolTest {
    @TempDir
    Path tempDir;

    @Test
    void validReadReturnsFileContentThroughRegistry() throws IOException {
        Files.writeString(tempDir.resolve("notes.txt"), "hello\nworld\n");
        ToolRegistry registry = registry();

        ToolResult result = registry.execute(call(input("notes.txt")), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("FILE: notes.txt"));
        assertTrue(result.content().contains("MODE: chars"));
        assertTrue(result.content().contains("hello\nworld\n"));
    }

    @Test
    void defaultReadLimitReadsTwelveThousandCharacters() throws IOException {
        String content = "a".repeat(11_500);
        Files.writeString(tempDir.resolve("long.txt"), content);
        ToolRegistry registry = registry();

        ToolResult result = registry.execute(call(input("long.txt")), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("END: 11500"));
        assertTrue(result.content().contains("TRUNCATED: no"));
        assertTrue(result.content().endsWith(content));
    }

    @Test
    void relativePathUsesToolContextCwd() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("inside.txt"), "from cwd");
        ToolRegistry registry = registry();

        ToolResult result = registry.execute(call(input("inside.txt")), context(workspace));

        assertFalse(result.error());
        assertTrue(result.content().contains("from cwd"));
    }

    @Test
    void missingPathFailsValidationThroughRegistry() {
        ToolRegistry registry = registry();

        ToolResult result = registry.execute(call(JsonNodeFactory.instance.objectNode()), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("path"));
    }

    @Test
    void invalidOffsetAndLimitFailValidationThroughRegistry() {
        ToolRegistry registry = registry();
        ObjectNode input = input("notes.txt");
        input.put("offset", -1);
        input.put("limit", 0);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("offset"));
        assertTrue(result.content().contains("limit"));
    }

    @Test
    void validationTrimsPathAndKeepsOffsetLimitRules() {
        ReadFileTool tool = new ReadFileTool(ReadFilePathAccess.fromPermissionService(allowingPermissionService()));
        ObjectNode input = input("  notes.txt  ");
        input.put("offset", 1);
        input.put("limit", 2);

        ValidationResult validation = tool.validateInput(input);

        assertTrue(validation.valid());
        assertEquals("notes.txt", validation.normalizedInput().orElseThrow().get("path").asText());
        assertEquals(1, validation.normalizedInput().orElseThrow().get("offset").asInt());
        assertEquals(2, validation.normalizedInput().orElseThrow().get("limit").asInt());
    }

    @Test
    void offsetAndLimitReadChunkAndReportTruncation() throws IOException {
        Files.writeString(tempDir.resolve("chunk.txt"), "0123456789");
        ToolRegistry registry = registry();
        ObjectNode input = input("chunk.txt");
        input.put("offset", 2);
        input.put("limit", 4);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("MODE: chars"));
        assertTrue(result.content().contains("OFFSET: 2"));
        assertTrue(result.content().contains("END: 6"));
        assertTrue(result.content().contains("TRUNCATED: yes"));
        assertTrue(result.content().endsWith("2345"));
    }

    @Test
    void lineStartAndLineCountReadOneBasedLineRange() throws IOException {
        Files.writeString(tempDir.resolve("lines.txt"), String.join("\n",
                "one",
                "two",
                "three",
                "four",
                "five") + "\n");
        ToolRegistry registry = registry();
        ObjectNode input = input("lines.txt");
        input.put("lineStart", 2);
        input.put("lineCount", 3);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("FILE: lines.txt"));
        assertTrue(result.content().contains("MODE: lines"));
        assertTrue(result.content().contains("LINE_START: 2"));
        assertTrue(result.content().contains("LINE_END: 4"));
        assertTrue(result.content().contains("TOTAL_LINES: 5"));
        assertTrue(result.content().contains("TRUNCATED: yes - call read_file again with lineStart 5"));
        assertTrue(result.content().endsWith("two\nthree\nfour\n"));
    }

    @Test
    void lineModeDefaultsLineCountAndReportsNoTruncationAtEnd() throws IOException {
        Files.writeString(tempDir.resolve("short.txt"), "alpha\nbeta\n");
        ToolRegistry registry = registry();
        ObjectNode input = input("short.txt");
        input.put("lineStart", 1);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("MODE: lines"));
        assertTrue(result.content().contains("LINE_START: 1"));
        assertTrue(result.content().contains("LINE_END: 2"));
        assertTrue(result.content().contains("TOTAL_LINES: 2"));
        assertTrue(result.content().contains("TRUNCATED: no"));
        assertTrue(result.content().endsWith("alpha\nbeta\n"));
    }

    @Test
    void lineModeDoesNotOverflowWhenLineStartIsNearIntegerMaxValue() throws IOException {
        Files.writeString(tempDir.resolve("short.txt"), "alpha\nbeta\n");
        ToolRegistry registry = registry();
        ObjectNode input = input("short.txt");
        input.put("lineStart", Integer.MAX_VALUE);
        input.put("lineCount", 2000);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertFalse(result.error());
        assertTrue(result.content().contains("MODE: lines"));
        assertTrue(result.content().contains("LINE_START: " + Integer.MAX_VALUE));
        assertTrue(result.content().contains("LINE_END: 2"));
        assertTrue(result.content().contains("TOTAL_LINES: 2"));
        assertTrue(result.content().contains("TRUNCATED: no"));
        assertFalse(result.content().contains("lineStart -"), result.content());
    }

    @Test
    void readFileRejectsMixedCharacterAndLineModes() {
        ToolRegistry registry = registry();
        ObjectNode input = input("notes.txt");
        input.put("offset", 0);
        input.put("lineStart", 1);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("read_file character mode offset/limit cannot be combined with line mode lineStart/lineCount"));
    }

    @Test
    void readFileLineModeRequiresLineStartWhenLineCountIsPresent() {
        ToolRegistry registry = registry();
        ObjectNode input = input("notes.txt");
        input.put("lineCount", 10);

        ToolResult result = registry.execute(call(input), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("read_file line mode requires lineStart"));
    }

    @Test
    void readFileLineCountAllowsTwoThousandAndRejectsAboveMax() {
        ReadFileTool tool = new ReadFileTool(ReadFilePathAccess.fromPermissionService(allowingPermissionService()));
        ObjectNode allowed = input("notes.txt");
        allowed.put("lineStart", 1);
        allowed.put("lineCount", 2000);
        ObjectNode rejected = input("notes.txt");
        rejected.put("lineStart", 1);
        rejected.put("lineCount", 2001);

        ValidationResult allowedValidation = tool.validateInput(allowed);
        ValidationResult rejectedValidation = tool.validateInput(rejected);

        assertTrue(allowedValidation.valid());
        assertFalse(rejectedValidation.valid());
        assertTrue(String.join("\n", rejectedValidation.errors()).contains("lineCount"));
    }

    @Test
    void inputSchemaIncludesLineModeFieldsAndApprovedDescriptions() {
        ReadFileTool tool = new ReadFileTool(ReadFilePathAccess.fromPermissionService(allowingPermissionService()));
        String schema = tool.inputSchema().toString();

        assertTrue(schema.contains("\"lineStart\""), schema);
        assertTrue(schema.contains("\"lineCount\""), schema);
        assertTrue(schema.contains("1-based line number to start reading from"), schema);
        assertTrue(schema.contains("Maximum is 2000"), schema);
        assertTrue(schema.contains("do not combine with lineStart or lineCount"), schema);
    }

    @Test
    void fileNotFoundReturnsErrorToolResult() {
        ToolRegistry registry = registry();

        ToolResult result = registry.execute(call(input("missing.txt")), context(tempDir));

        assertTrue(result.error());
        assertTrue(result.content().contains("File not found"));
    }

    @Test
    void permissionServiceDenialReturnsErrorToolResultWithFeedback() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "secret");
        ToolRegistry registry = new ToolRegistry();
        PermissionService permissionService = new PromptingPermissionService(
                PermissionPromptHandler.deny(PermissionDecision.DENY_WITH_FEEDBACK, "Explain why this file is needed")
        );
        registry.register(new ReadFileTool(ReadFilePathAccess.fromPermissionService(permissionService)));

        ToolResult result = registry.execute(call(input(secret.toString())), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("Explain why this file is needed"));
    }

    @Test
    void defaultReadFileToolDoesNotAllowCwdOutsidePathSilentlyWithoutPermissionHandler() throws IOException {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path notes = Files.writeString(tempDir.resolve("notes.txt"), "hello");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());

        ToolResult result = registry.execute(call(input(notes.toString())), context(workspace));

        assertTrue(result.error());
        assertTrue(result.content().contains("No permission prompt handler"));
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(ReadFilePathAccess.fromPermissionService(allowingPermissionService())));
        return registry;
    }

    private static PermissionService allowingPermissionService() {
        return new PromptingPermissionService(PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE));
    }

    private static ToolCall call(ObjectNode input) {
        return new ToolCall("tool-use-1", "read_file", input);
    }

    private static ObjectNode input(String path) {
        return JsonNodeFactory.instance.objectNode().put("path", path);
    }

    private static ToolContext context(Path cwd) {
        return new ToolContext(cwd, "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }
}
