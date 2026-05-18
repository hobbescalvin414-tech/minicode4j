package minicode.app.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public final class UiStdioMockBackend {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void run(Path cwd, OutputStream output) {
        PrintWriter writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
        for (ObjectNode event : events(cwd.toAbsolutePath().normalize())) {
            writer.println(toJson(event));
        }
        writer.flush();
    }

    private static List<ObjectNode> events(Path cwd) {
        return List.of(
                object("ready")
                        .put("sessionId", "mock-session")
                        .put("cwd", cwd.toString())
                        .put("model", "mock-ui-model"),
                object("history_item")
                        .put("id", "history-1")
                        .put("kind", "user")
                        .put("text", "Show the UI protocol mock."),
                object("assistant_message")
                        .put("id", "assistant-1")
                        .put("text", "This is a fixed assistant message from the Java mock backend."),
                object("assistant_progress")
                        .put("id", "progress-1")
                        .put("text", "Preparing UI event stream."),
                object("tool_started")
                        .put("toolUseId", "tool-1")
                        .put("toolName", "read_file")
                        .put("summary", "path=README.md"),
                object("tool_finished")
                        .put("toolUseId", "tool-1")
                        .put("toolName", "read_file")
                        .put("status", "ok")
                        .put("summary", "path=README.md")
                        .put("preview", "FILE: README.md\nMiniCode4j mock preview")
                        .put("truncated", true)
                        .put("hiddenLines", 12)
                        .putNull("storageRef"),
                permissionRequest(),
                object("permission_audit")
                        .put("requestId", "req-1")
                        .put("decision", "allowed")
                        .put("choiceKey", "allow_once")
                        .put("summary", "allowed allow_once"),
                object("await_user")
                        .put("toolUseId", "tool-2")
                        .put("question", "Which file should the mock inspect next?"),
                object("context_stats")
                        .put("badge", "context 5% normal 56k/1.0M provider")
                        .put("totalTokens", 56138)
                        .put("effectiveInput", 1032576)
                        .put("source", "provider")
                        .put("warning", "normal"),
                object("status")
                        .put("text", "Ready")
                        .put("busy", false),
                object("turn_stop")
                        .put("reason", "FINAL")
        );
    }

    private static ObjectNode permissionRequest() {
        ObjectNode event = object("permission_request")
                .put("requestId", "req-1")
                .put("title", "Command execution")
                .put("body", "The model requested command execution.");
        event.set("facts", MAPPER.createArrayNode().add("Command: mvn test"));
        event.set("choices", MAPPER.createArrayNode()
                .add(choice("allow_once", "Allow once"))
                .add(choice("deny_feedback", "Deny with feedback")));
        return event;
    }

    private static ObjectNode choice(String key, String label) {
        return MAPPER.createObjectNode().put("key", key).put("label", label);
    }

    private static ObjectNode object(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    private static String toJson(ObjectNode event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode UI event.", exception);
        }
    }
}
