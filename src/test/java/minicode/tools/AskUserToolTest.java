package minicode.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.builtin.AskUserTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AskUserToolTest {
    @Test
    void metadataNameAndSchemaAreCorrect() {
        AskUserTool tool = new AskUserTool();

        assertEquals("ask_user", tool.metadata().name());
        assertSame(tool.inputSchema(), tool.metadata().inputSchema());
        assertTrue(tool.inputSchema().has("properties"));
        assertTrue(tool.inputSchema().get("properties").has("question"));
    }

    @Test
    void validInputReturnsAwaitUserTrue() {
        AskUserTool tool = new AskUserTool();

        ValidationResult validation = tool.validateInput(questionInput("  What should I do next?  "));
        assertTrue(validation.valid());
        assertEquals("What should I do next?", validation.normalizedInput().orElseThrow().get("question").asText());

        ToolResult result = tool.run(validation.normalizedInput().orElseThrow(), context());

        assertFalse(result.error());
        assertTrue(result.awaitUser());
        assertTrue(result.content().contains("What should I do next?"));
    }

    @Test
    void missingQuestionFailsValidation() {
        AskUserTool tool = new AskUserTool();

        ValidationResult validation = tool.validateInput(JsonNodeFactory.instance.objectNode());

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(0).contains("question"));
    }

    @Test
    void blankQuestionFailsValidation() {
        AskUserTool tool = new AskUserTool();

        ValidationResult validation = tool.validateInput(questionInput("   "));

        assertFalse(validation.valid());
        assertTrue(validation.errors().get(0).contains("question"));
    }

    @Test
    void toolRegistryExecutionReturnsAwaitUserTrue() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AskUserTool());

        ToolResult result = registry.execute(new ToolCall("tool-use-1", "ask_user", questionInput("Need input")), context());

        assertFalse(result.error());
        assertTrue(result.awaitUser());
        assertTrue(result.content().contains("Need input"));
    }

    private static ObjectNode questionInput(String question) {
        return JsonNodeFactory.instance.objectNode().put("question", question);
    }

    private static ToolContext context() {
        return new ToolContext(Path.of("."), "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }
}
