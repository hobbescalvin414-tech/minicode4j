package minicode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void registersAndExecutesFakeTool() {
        FakeTool tool = new FakeTool(
                "fake_tool",
                input -> ValidationResult.valid(input),
                (input, context) -> ToolResult.ok("ran:" + input.get("value").asText())
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolResult result = registry.execute(call("fake_tool", "hello"), context());

        assertFalse(result.error());
        assertEquals("ran:hello", result.content());
    }

    @Test
    void passesNormalizedInputToRun() {
        ObjectNode normalized = JsonNodeFactory.instance.objectNode().put("value", "normalized");
        CapturingFakeTool tool = new CapturingFakeTool(
                "normalize_tool",
                input -> ValidationResult.valid(normalized),
                (input, context) -> ToolResult.ok(input.get("value").asText())
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolResult result = registry.execute(call("normalize_tool", "raw"), context());

        assertFalse(result.error());
        assertEquals("normalized", result.content());
        assertEquals(normalized, tool.lastRunInput);
    }

    @Test
    void unknownToolReturnsErrorToolResult() {
        ToolRegistry registry = new ToolRegistry();

        ToolResult result = registry.execute(call("missing_tool", "hello"), context());

        assertTrue(result.error());
        assertTrue(result.content().contains("Unknown tool"));
    }

    @Test
    void validationFailureReturnsErrorToolResult() {
        FakeTool tool = new FakeTool(
                "invalid_tool",
                input -> ValidationResult.invalid(List.of("missing required field")),
                (input, context) -> ToolResult.ok("should not run")
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolResult result = registry.execute(call("invalid_tool", "hello"), context());

        assertTrue(result.error());
        assertTrue(result.content().contains("missing required field"));
    }

    @Test
    void toolExceptionReturnsErrorToolResult() {
        FakeTool tool = new FakeTool(
                "boom_tool",
                input -> ValidationResult.valid(input),
                (input, context) -> {
                    throw new IllegalStateException("boom");
                }
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolResult result = registry.execute(call("boom_tool", "hello"), context());

        assertTrue(result.error());
        assertTrue(result.content().contains("boom"));
    }

    @Test
    void nullValidationResultReturnsErrorToolResult() {
        FakeTool tool = new FakeTool(
                "null_validation_tool",
                input -> null,
                (input, context) -> ToolResult.ok("should not run")
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolResult result = registry.execute(call("null_validation_tool", "hello"), context());

        assertTrue(result.error());
        assertTrue(result.content().contains("validation"));
    }

    @Test
    void validValidationWithoutNormalizedInputReturnsErrorToolResult() {
        FakeTool tool = new FakeTool(
                "missing_normalized_tool",
                input -> new ValidationResult(true, java.util.Optional.empty(), List.of()),
                (input, context) -> ToolResult.ok("should not run")
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolResult result = registry.execute(call("missing_normalized_tool", "hello"), context());

        assertTrue(result.error());
        assertTrue(result.content().contains("normalized"));
    }

    @Test
    void passesToolContextToRun() {
        CapturingFakeTool tool = new CapturingFakeTool(
                "context_tool",
                input -> ValidationResult.valid(input),
                (input, context) -> ToolResult.ok(context.sessionId() + ":" + context.toolUseId().orElseThrow())
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolContext context = context();

        ToolResult result = registry.execute(call("context_tool", "hello"), context);

        assertFalse(result.error());
        assertEquals("session-1:tool-use-1", result.content());
        assertEquals(context, tool.lastToolContext);
    }

    private static ToolCall call(String toolName, String value) {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("value", value);
        return new ToolCall("tool-use-1", toolName, input);
    }

    private static ToolContext context() {
        return new ToolContext(Path.of("."), "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }

    private record FakeTool(String name, Validator validator, Runner runner) implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    name,
                    "fake tool",
                    JsonNodeFactory.instance.objectNode(),
                    ToolOrigin.BUILTIN,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE
            );
        }

        @Override
        public JsonNode inputSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public ValidationResult validateInput(JsonNode input) {
            return validator.validate(input);
        }

        @Override
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            return runner.run(normalizedInput, toolContext);
        }
    }

    private static final class CapturingFakeTool implements Tool {
        private final String name;
        private final Validator validator;
        private final Runner runner;
        private JsonNode lastRunInput;
        private ToolContext lastToolContext;

        private CapturingFakeTool(String name, Validator validator, Runner runner) {
            this.name = name;
            this.validator = validator;
            this.runner = runner;
        }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    name,
                    "capturing fake tool",
                    JsonNodeFactory.instance.objectNode(),
                    ToolOrigin.BUILTIN,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE
            );
        }

        @Override
        public JsonNode inputSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public ValidationResult validateInput(JsonNode input) {
            return validator.validate(input);
        }

        @Override
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            lastRunInput = normalizedInput;
            lastToolContext = toolContext;
            return runner.run(normalizedInput, toolContext);
        }
    }

    @FunctionalInterface
    private interface Validator {
        ValidationResult validate(JsonNode input);
    }

    @FunctionalInterface
    private interface Runner {
        ToolResult run(JsonNode input, ToolContext toolContext);
    }
}
