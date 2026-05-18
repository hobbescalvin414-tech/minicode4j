package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.model.MockModelAdapter;
import minicode.tools.api.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStdioRunTurnBackendTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void userSubmitRunsMockProviderTurnAndProjectsUiEvents() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("README.md"), "MiniCode4j\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "list workspace"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend(MockModelAdapter.toolThenFinal(
                new ToolCall("tool-use-1", "list_files",
                        JsonNodeFactory.instance.objectNode()
                                .put("path", ".")
                                .put("maxDepth", 1)
                                .put("limit", 20)),
                "mock runTurn final"
        )).run(home, workspace, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .toList();
        assertEquals("ready", events.getFirst().get("type").asText());
        assertEquals("ui-session", events.getFirst().get("sessionId").asText());
        assertEquals(workspace.toString(), events.getFirst().get("cwd").asText());
        assertTrue(events.stream().anyMatch(event -> isStatus(event, "Thinking...", true)));
        JsonNode contextStats = events.stream()
                .filter(event -> isType(event, "context_stats"))
                .findFirst()
                .orElseThrow();
        assertEquals("estimate", contextStats.get("source").asText());
        assertTrue(contextStats.get("badge").asText().contains(" estimate"));
        assertFalse(contextStats.get("badge").asText().contains("estimate_only"));
        assertTrue(events.stream().anyMatch(event -> isToolStarted(event, "tool-use-1", "list_files", "path=.")));
        assertTrue(events.stream().anyMatch(event -> isToolFinished(event, "tool-use-1", "list_files", "ok",
                "path=.", "README.md")));
        assertTrue(events.stream().anyMatch(event -> isAssistantMessage(event, "mock runTurn final")));
        assertEquals("turn_stop", events.getLast().get("type").asText());
        assertEquals("FINAL", events.getLast().get("reason").asText());
        String text = output.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(text.contains("system prompt"));
        assertFalse(text.contains(".jsonl"));
    }

    @Test
    void permissionResponseContinuesControlledMockFlow() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "mock permission flow"),
                jsonObject("type", "permission_response", "requestId", "mock-permission-1",
                        "choiceKey", "allow_once"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend().run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .toList();
        assertTrue(events.stream().anyMatch(event -> isPermissionRequest(event, "mock-permission-1",
                "Command execution", "allow_once", "deny_once")));
        assertTrue(events.stream().anyMatch(event -> isPermissionAudit(event, "mock-permission-1",
                "allowed", "allow_once")));
        assertTrue(events.stream().anyMatch(event -> isAssistantMessage(event,
                "Mock permission flow continued after approval.")));
        assertEquals("turn_stop", events.getLast().get("type").asText());
        assertEquals("FINAL", events.getLast().get("reason").asText());
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void permissionDeniedWithFeedbackContinuesControlledMockFlow() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "mock permission flow"),
                jsonObject("type", "permission_response", "requestId", "mock-permission-1",
                        "choiceKey", "deny_feedback", "feedback", "Please explain first."),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend().run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .toList();
        assertTrue(events.stream().anyMatch(event -> isPermissionAudit(event, "mock-permission-1",
                "denied", "deny_feedback")));
        assertTrue(events.stream().anyMatch(event -> isAssistantMessage(event,
                "Mock permission flow continued after denial feedback: Please explain first.")));
        assertEquals("FINAL", events.getLast().get("reason").asText());
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void askUserAnswerContinuesControlledMockFlow() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "mock ask_user flow"),
                jsonObject("type", "ask_user_answer", "toolUseId", "mock-ask-user-1",
                        "text", "Use README.md"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend().run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .toList();
        assertTrue(events.stream().anyMatch(event -> isAwaitUser(event, "mock-ask-user-1",
                "Which file should the mock flow use?")));
        assertTrue(events.stream().anyMatch(event -> isTurnStop(event, "AWAIT_USER")));
        assertTrue(events.stream().anyMatch(event -> isAssistantMessage(event,
                "Mock ask_user flow continued with answer: Use README.md")));
        assertEquals("FINAL", events.getLast().get("reason").asText());
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void controlledToolDisplayMockFlowProjectsToolPreviewMetadata() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "/mock tool"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend().run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .toList();
        assertTrue(events.stream().anyMatch(event -> isToolStarted(event, "mock-tool-success",
                "read_file", "path=README.md")));
        assertTrue(events.stream().anyMatch(event -> isToolFinished(event, "mock-tool-success",
                "read_file", "ok", "path=README.md", "MiniCode4j mock preview")));
        JsonNode large = events.stream()
                .filter(event -> isToolFinished(event, "mock-tool-large", "run_command", "ok",
                        "cmd=\"mvn test\"", "line 1"))
                .findFirst()
                .orElseThrow();
        assertTrue(large.get("truncated").asBoolean());
        assertTrue(large.get("hiddenLines").asInt() > 0);
        assertEquals("mock-storage-run-command-1", large.get("storageRef").asText());
        assertTrue(events.stream().anyMatch(event -> isStatus(event, "Ready", false)));
        assertEquals("FINAL", events.getLast().get("reason").asText());
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void controlledDiffMockFlowProjectsDiffPreview() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "/mock diff"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend().run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        JsonNode finished = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .filter(event -> isToolFinished(event, "mock-tool-diff", "edit_file", "ok",
                        "path=README.md", "Updated README.md"))
                .findFirst()
                .orElseThrow();
        assertTrue(finished.has("diffPreview"));
        assertEquals("README.md", finished.get("diffPreview").get("title").asText());
        assertTrue(finished.get("diffPreview").get("lines").toString().contains("+New line"));
        assertTrue(finished.get("diffPreview").get("truncated").asBoolean());
        assertEquals(5, finished.get("diffPreview").get("hiddenLines").asInt());
        assertTrue(output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .anyMatch(event -> isStatus(event, "Ready", false)));
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void controlledFailedToolMockFlowProjectsErrorStatus() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "/mock tool-fail"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend().run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 3);

        assertTrue(output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .anyMatch(event -> isToolFinished(event, "mock-tool-fail", "run_command",
                        "error", "cmd=\"mvn test\"", "BUILD FAILURE")));
        assertTrue(output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .anyMatch(event -> isStatus(event, "Ready", false)));
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void mockRunUnknownToolInputDoesNotLeakRawJsonSummary() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-session", "maxSteps", 2),
                jsonObject("type", "user_submit", "text", "run unknown tool"),
                jsonObject("type", "shutdown")) + "\n";

        new UiStdioRunTurnBackend(MockModelAdapter.toolThenFinal(
                new ToolCall("tool-unknown-1", "unknown_tool",
                        JsonNodeFactory.instance.objectNode()
                                .put("providerRawPayload", "raw-provider-payload")
                                .put("ANTHROPIC_AUTH_TOKEN", "sk-raw-secret")
                                .put("systemPrompt", "system prompt must not leak")
                                .put("toolSchema", "{\"properties\":{\"secret\":true}}")
                                .put("sessionPath", "E:\\Minicode-Java\\.home\\sessions\\session.jsonl")),
                "mock final"
        )).run(home, workspace, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 2);

        JsonNode started = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRunTurnBackendTest::parseJson)
                .filter(event -> isToolStarted(event, "tool-unknown-1", "unknown_tool", "input hidden"))
                .findFirst()
                .orElseThrow();
        String summary = started.get("summary").asText();
        assertFalse(summary.startsWith("{"), summary);
        assertFalse(summary.contains("raw-provider-payload"), summary);
        assertFalse(summary.contains("sk-raw-secret"), summary);
        assertFalse(summary.contains("system prompt must not leak"), summary);
        assertFalse(summary.contains("toolSchema"), summary);
        assertFalse(summary.contains(".jsonl"), summary);
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    private static boolean isType(JsonNode event, String type) {
        return event.has("type") && type.equals(event.get("type").asText());
    }

    private static boolean isStatus(JsonNode event, String text, boolean busy) {
        return isType(event, "status")
                && text.equals(event.get("text").asText())
                && busy == event.get("busy").asBoolean();
    }

    private static boolean isToolStarted(JsonNode event, String toolUseId, String toolName, String summaryPart) {
        return isType(event, "tool_started")
                && toolUseId.equals(event.get("toolUseId").asText())
                && toolName.equals(event.get("toolName").asText())
                && event.get("summary").asText().contains(summaryPart);
    }

    private static boolean isToolFinished(JsonNode event, String toolUseId, String toolName, String status,
                                          String summaryPart, String previewPart) {
        return isType(event, "tool_finished")
                && toolUseId.equals(event.get("toolUseId").asText())
                && toolName.equals(event.get("toolName").asText())
                && status.equals(event.get("status").asText())
                && event.get("summary").asText().contains(summaryPart)
                && event.get("preview").asText().contains(previewPart);
    }

    private static boolean isAssistantMessage(JsonNode event, String text) {
        return isType(event, "assistant_message") && text.equals(event.get("text").asText());
    }

    private static boolean isPermissionRequest(JsonNode event, String requestId, String title,
                                               String firstChoice, String secondChoice) {
        return isType(event, "permission_request")
                && requestId.equals(event.get("requestId").asText())
                && title.equals(event.get("title").asText())
                && event.get("choices").toString().contains(firstChoice)
                && event.get("choices").toString().contains(secondChoice);
    }

    private static boolean isPermissionAudit(JsonNode event, String requestId, String decision, String choiceKey) {
        return isType(event, "permission_audit")
                && requestId.equals(event.get("requestId").asText())
                && decision.equals(event.get("decision").asText())
                && choiceKey.equals(event.get("choiceKey").asText());
    }

    private static boolean isAwaitUser(JsonNode event, String toolUseId, String question) {
        return isType(event, "await_user")
                && toolUseId.equals(event.get("toolUseId").asText())
                && question.equals(event.get("question").asText());
    }

    private static boolean isTurnStop(JsonNode event, String reason) {
        return isType(event, "turn_stop") && reason.equals(event.get("reason").asText());
    }

    private static void assertNoSensitiveLeak(String text) {
        assertFalse(text.contains("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(text.contains("system prompt"));
        assertFalse(text.contains(".jsonl"));
        assertFalse(text.contains("api key"));
        assertFalse(text.contains("token"));
    }

    private static String jsonObject(Object... pairs) throws Exception {
        var node = MAPPER.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value instanceof Integer integer) {
                node.put((String) pairs[index], integer);
            } else {
                node.put((String) pairs[index], value.toString());
            }
        }
        return MAPPER.writeValueAsString(node);
    }

    private static JsonNode parseJson(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception exception) {
            throw new AssertionError("Invalid JSONL line: " + line, exception);
        }
    }
}
