package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.message.UserMessage;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.model.MockModelAdapter;
import minicode.session.store.SessionStore;
import minicode.tools.api.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStdioRealBackendTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void realBackendWithMockRuntimeConfigRunsTurnThroughStrictUiEvents() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-real-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "hello"),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model")).run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 4);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRealBackendTest::parseJson)
                .toList();
        assertEquals("ready", events.getFirst().get("type").asText());
        assertEquals("ui-real-session", events.getFirst().get("sessionId").asText());
        assertEquals(workspace.toString(), events.getFirst().get("cwd").asText());
        assertEquals("mock-model", events.getFirst().get("model").asText());
        assertTrue(events.stream().anyMatch(event -> isStatus(event, "Thinking...", true)));
        assertTrue(events.stream().anyMatch(event -> isType(event, "context_stats")));
        assertTrue(events.stream().anyMatch(event -> isAssistantMessage(event, "mock final")));
        assertTrue(events.stream().anyMatch(event -> isTurnStop(event, "FINAL")));
        assertNoSensitiveLeak(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void initProjectsHistoryFromJavaSessionStoreWithoutExposingJsonlPath() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        minicode.session.store.SessionStore store = new minicode.session.store.SessionStore(home.resolve("sessions"));
        store.append(new minicode.session.factory.SessionEventFactory("ui-real-session", workspace.toString())
                .message(new minicode.core.message.UserMessage("previous turn")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-real-session", "maxSteps", 4),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model")).run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 4);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"type\":\"history_item\""), text);
        assertTrue(text.contains("previous turn"), text);
        assertFalse(text.contains(".jsonl"), text);
        assertNoSensitiveLeak(text);
    }

    @Test
    void userSubmitPersistsUserMessageAndResumeProjectsUserHistory() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        String sessionId = "ui-real-session";
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        String firstInput = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", sessionId, "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "persist me"),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model")).run(home, workspace,
                new ByteArrayInputStream(firstInput.getBytes(StandardCharsets.UTF_8)), firstOutput, 4);

        SessionStore store = new SessionStore(home.resolve("sessions"));
        assertTrue(store.readAll(sessionId, workspace.toString()).stream()
                        .anyMatch(event -> event.message().orElse(null) instanceof UserMessage user
                                && "persist me".equals(user.content())),
                firstOutput.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream resumeOutput = new ByteArrayOutputStream();
        String resumeInput = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "resumeSessionId", sessionId, "maxSteps", 4),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model")).run(home, workspace,
                new ByteArrayInputStream(resumeInput.getBytes(StandardCharsets.UTF_8)), resumeOutput, 4);

        List<JsonNode> resumeEvents = resumeOutput.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRealBackendTest::parseJson)
                .toList();
        assertTrue(resumeEvents.stream().anyMatch(event -> isHistoryItem(event, "user", "persist me")),
                resumeOutput.toString(StandardCharsets.UTF_8));
    }

    @Test
    void resumeHistoryDoesNotProjectInternalProgressContinuationAsUserHistory() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        String sessionId = "ui-real-progress-session";
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        String firstInput = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", sessionId, "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "real user request"),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model"), new MockModelAdapter(List.of(
                new AssistantStep("working", AssistantKind.PROGRESS),
                new AssistantStep("done", AssistantKind.FINAL)
        ))).run(home, workspace, new ByteArrayInputStream(firstInput.getBytes(StandardCharsets.UTF_8)),
                firstOutput, 4);

        ByteArrayOutputStream resumeOutput = new ByteArrayOutputStream();
        String resumeInput = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "resumeSessionId", sessionId, "maxSteps", 4),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model")).run(home, workspace,
                new ByteArrayInputStream(resumeInput.getBytes(StandardCharsets.UTF_8)), resumeOutput, 4);

        List<JsonNode> resumeEvents = resumeOutput.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRealBackendTest::parseJson)
                .toList();
        assertTrue(resumeEvents.stream().anyMatch(event -> isHistoryItem(event, "user", "real user request")),
                resumeOutput.toString(StandardCharsets.UTF_8));
        assertFalse(resumeEvents.stream().anyMatch(event -> isHistoryItemContaining(event, "user",
                        "Continue immediately from your <progress>")),
                resumeOutput.toString(StandardCharsets.UTF_8));
    }

    @Test
    void resumeSessionFromDifferentCwdReportsErrorInsteadOfStartingEmptySession() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path originalWorkspace = tempDir.resolve("original-workspace").toAbsolutePath().normalize();
        Path currentWorkspace = tempDir.resolve("current-workspace").toAbsolutePath().normalize();
        Files.createDirectories(originalWorkspace);
        Files.createDirectories(currentWorkspace);
        String sessionId = "ui-real-other-cwd-session";
        new SessionStore(home.resolve("sessions"))
                .append(new minicode.session.factory.SessionEventFactory(sessionId, originalWorkspace.toString())
                        .message(new UserMessage("from original cwd")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", currentWorkspace.toString(), "home", home.toString(),
                        "resumeSessionId", sessionId, "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "must not start a new session"),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model")).run(home, currentWorkspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 4);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRealBackendTest::parseJson)
                .toList();
        assertFalse(events.stream().anyMatch(event -> isType(event, "ready")), output.toString(StandardCharsets.UTF_8));
        assertFalse(events.stream().anyMatch(event -> isAssistantMessage(event, "mock final")),
                output.toString(StandardCharsets.UTF_8));
        assertTrue(events.stream().anyMatch(event -> isFatalErrorContaining(event,
                        "belongs to a different cwd")),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void askUserAnswerAcceptedImmediatelyAfterAwaitUserEvent() throws Exception {
        Path home = tempDir.resolve("home").toAbsolutePath().normalize();
        Path workspace = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String input = String.join("\n",
                jsonObject("type", "init", "cwd", workspace.toString(), "home", home.toString(),
                        "sessionId", "ui-real-session", "maxSteps", 4),
                jsonObject("type", "user_submit", "text", "ask"),
                jsonObject("type", "ask_user_answer", "toolUseId", "ask-tool-1", "text", "README.md"),
                jsonObject("type", "shutdown")) + "\n";

        UiStdioRealBackend.real(runtimeConfig("mock-model"), MockModelAdapter.toolThenFinal(
                new ToolCall("ask-tool-1", "ask_user",
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                .put("question", "Which file?")),
                "answer accepted")).run(home, workspace,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, 4);

        List<JsonNode> events = output.toString(StandardCharsets.UTF_8).lines()
                .map(UiStdioRealBackendTest::parseJson)
                .toList();
        assertTrue(events.stream().anyMatch(event -> isType(event, "await_user")));
        assertTrue(events.stream().anyMatch(event -> isTurnStop(event, "AWAIT_USER")));
        assertTrue(events.stream().anyMatch(event -> isAssistantMessage(event, "answer accepted")));
        assertFalse(events.stream().anyMatch(event -> isType(event, "tool_started")
                && "ask_user".equals(event.get("toolName").asText())), output.toString(StandardCharsets.UTF_8));
        assertFalse(events.stream().anyMatch(event -> isType(event, "tool_finished")
                && "ask_user".equals(event.get("toolName").asText())), output.toString(StandardCharsets.UTF_8));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("Question for user:"),
                output.toString(StandardCharsets.UTF_8));
        assertFalse(events.stream().anyMatch(event -> isType(event, "error")
                && event.get("message").asText().contains("busy")), output.toString(StandardCharsets.UTF_8));
    }

    private static RuntimeConfig runtimeConfig(String model) {
        return new RuntimeConfig(
                ProviderKind.MOCK,
                model,
                "https://example.invalid",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(4),
                Duration.ofSeconds(300),
                "test",
                Map.of()
        );
    }

    private static boolean isType(JsonNode event, String type) {
        return event.has("type") && type.equals(event.get("type").asText());
    }

    private static boolean isStatus(JsonNode event, String text, boolean busy) {
        return isType(event, "status")
                && text.equals(event.get("text").asText())
                && busy == event.get("busy").asBoolean();
    }

    private static boolean isAssistantMessage(JsonNode event, String text) {
        return isType(event, "assistant_message") && text.equals(event.get("text").asText());
    }

    private static boolean isTurnStop(JsonNode event, String reason) {
        return isType(event, "turn_stop") && reason.equals(event.get("reason").asText());
    }

    private static boolean isHistoryItem(JsonNode event, String kind, String text) {
        return isType(event, "history_item")
                && kind.equals(event.get("kind").asText())
                && text.equals(event.get("text").asText());
    }

    private static boolean isHistoryItemContaining(JsonNode event, String kind, String text) {
        return isType(event, "history_item")
                && kind.equals(event.get("kind").asText())
                && event.get("text").asText().contains(text);
    }

    private static boolean isFatalErrorContaining(JsonNode event, String text) {
        return isType(event, "error")
                && !event.get("recoverable").asBoolean()
                && event.get("message").asText().contains(text);
    }

    private static void assertNoSensitiveLeak(String text) {
        assertFalse(text.contains("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(text.contains("api key"));
        assertFalse(text.contains("system prompt"));
        assertFalse(text.contains(".jsonl"));
        assertFalse(text.contains("tool schema"));
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
