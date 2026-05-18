package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStdioMockBackendTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsFixedProviderNeutralUiEventJsonl() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();

        new UiStdioMockBackend().run(workspace, output);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioMockBackendTest::parseJson)
                .toList();
        assertEquals(List.of(
                        "ready",
                        "history_item",
                        "assistant_message",
                        "assistant_progress",
                        "tool_started",
                        "tool_finished",
                        "permission_request",
                        "permission_audit",
                        "await_user",
                        "context_stats",
                        "status",
                        "turn_stop"),
                events.stream().map(event -> event.get("type").asText()).toList());
        assertEquals(workspace.toString(), events.getFirst().get("cwd").asText());
        assertEquals("read_file", events.get(4).get("toolName").asText());
        assertEquals("allow_once", events.get(6).get("choices").get(0).get("key").asText());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("system prompt"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(".jsonl"));
    }

    private static JsonNode parseJson(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception exception) {
            throw new AssertionError("Invalid JSONL line: " + line, exception);
        }
    }
}
