package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.context.accounting.TokenAccountingResult;
import minicode.context.accounting.UsageBoundary;
import minicode.context.stats.ContextStats;
import minicode.context.stats.ContextWarningLevel;
import minicode.core.event.AgentEvent;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.edit.EditReview;
import minicode.model.ProviderThinkingBlock;
import minicode.permissions.model.CommandClassification;
import minicode.permissions.model.CommandSignature;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionRequest;
import minicode.permissions.model.PermissionRequestDetails;
import minicode.permissions.model.PermissionRequestKind;
import minicode.permissions.model.PermissionResource;
import minicode.permissions.model.PermissionScope;
import minicode.session.model.SessionEvent;
import minicode.tools.result.ToolResultReplacementRecord;
import minicode.tools.result.ToolResultReplacementTrigger;
import minicode.tools.result.ToolResultStorageRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStrictProjectionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-05-17T00:00:00Z");
    private static final String SECRET_TOKEN = "sk-ts-ui-7-secret";
    private static final String SYSTEM_PROMPT = "SYSTEM PROMPT: do not leak this prompt";
    private static final String TOOL_SCHEMA = "{\"schema\":{\"properties\":{\"api_key\":\"secret\"}}}";
    private static final String SESSION_JSONL = "E:\\Minicode-Java\\.home\\sessions\\cwd\\session.jsonl";
    private static final String STORAGE_PATH = "E:\\Minicode-Java\\.home\\tool-results\\stored-output.txt";

    @Test
    void encoderUsesExactKeySetForEveryOutboundEventType() throws Exception {
        UiEventEncoder encoder = new UiEventEncoder();
        List<UiEvent> events = List.of(
                new UiEvent.Ready("session-1", "E:\\work", "mock-model"),
                new UiEvent.HistoryItem("event-1", "user", "hello"),
                new UiEvent.AssistantMessage("event-2", "final"),
                new UiEvent.AssistantProgress("event-3", "progress"),
                new UiEvent.ToolStarted("tool-1", "read_file", "path=README.md"),
                new UiEvent.ToolFinished("tool-1", "read_file", "ok", "path=README.md",
                        "preview", false, 0, null, Optional.of(diffPreview())),
                new UiEvent.PermissionRequest("req-1", "Command execution", "body",
                        List.of("Command: mvn test"),
                        List.of(new UiEvent.PermissionChoice("allow_once", "Allow once"))),
                new UiEvent.PermissionAudit("req-1", "allowed", "allow_once", "allowed allow_once"),
                new UiEvent.AwaitUser("tool-ask-1", "Which file?"),
                contextStats(TokenAccountingResult.providerUsage(10, 2, 12,
                        new UsageBoundary(0, Optional.of("message-1")))),
                new UiEvent.Status("Thinking...", true),
                new UiEvent.TurnStop("MAX_STEPS", Optional.of("Type continue")),
                new UiEvent.Error("recoverable", true)
        );

        Map<String, Set<String>> expected = Map.ofEntries(
                Map.entry("ready", Set.of("type", "sessionId", "cwd", "model")),
                Map.entry("history_item", Set.of("type", "id", "kind", "text")),
                Map.entry("assistant_message", Set.of("type", "id", "text")),
                Map.entry("assistant_progress", Set.of("type", "id", "text")),
                Map.entry("tool_started", Set.of("type", "toolUseId", "toolName", "summary")),
                Map.entry("tool_finished", Set.of("type", "toolUseId", "toolName", "status", "summary",
                        "preview", "truncated", "hiddenLines", "storageRef", "diffPreview")),
                Map.entry("permission_request", Set.of("type", "requestId", "title", "body", "facts", "choices")),
                Map.entry("permission_audit", Set.of("type", "requestId", "decision", "choiceKey", "summary")),
                Map.entry("await_user", Set.of("type", "toolUseId", "question")),
                Map.entry("context_stats", Set.of("type", "badge", "totalTokens", "effectiveInput", "source", "warning")),
                Map.entry("status", Set.of("type", "text", "busy")),
                Map.entry("turn_stop", Set.of("type", "reason", "message")),
                Map.entry("error", Set.of("type", "message", "recoverable"))
        );

        for (UiEvent event : events) {
            JsonNode json = MAPPER.readTree(encoder.encode(event));
            assertEquals(expected.get(json.get("type").asText()), iterableToSet(json.fieldNames()), json.toString());
            if (json.has("diffPreview")) {
                assertEquals(Set.of("title", "lines", "truncated", "hiddenLines"),
                        iterableToSet(json.get("diffPreview").fieldNames()), json.toString());
            }
        }
    }

    @Test
    void outboundDtoRejectsInvalidEnumLikeFieldsAtConstructionTime() {
        assertThrows(IllegalArgumentException.class,
                () -> new UiEvent.HistoryItem("event-1", "system", "hidden"));
        assertThrows(IllegalArgumentException.class,
                () -> new UiEvent.ContextStats("context", 1, 100, "provider_raw", "normal"));
        assertThrows(IllegalArgumentException.class,
                () -> new UiEvent.ContextStats("context", 1, 100, "provider", "unknown_warning"));
        assertThrows(IllegalArgumentException.class,
                () -> new UiEvent.TurnStop("PAUSE_TURN_RAW", Optional.empty()));
    }

    @Test
    void unknownToolInputDoesNotUseJsonToStringOrLeakProviderPayloads() {
        UiAgentEventProjector projector = new UiAgentEventProjector();
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("providerRawPayload", "raw-provider-payload-" + SECRET_TOKEN)
                .put("ANTHROPIC_AUTH_TOKEN", SECRET_TOKEN)
                .put("systemPrompt", SYSTEM_PROMPT)
                .put("toolSchema", TOOL_SCHEMA)
                .put("sessionPath", SESSION_JSONL);

        List<UiEvent> projected = projector.project(new AgentEvent.ToolStartedEvent(
                "turn-1", NOW, "tool-unknown-1", "unknown_tool", input));

        UiEvent.ToolStarted started = assertInstanceOf(UiEvent.ToolStarted.class, projected.getFirst());
        assertEquals("unknown_tool", started.toolName());
        assertFalse(started.summary().contains("providerRawPayload"), started.summary());
        assertFalse(started.summary().contains(SECRET_TOKEN), started.summary());
        assertFalse(started.summary().contains(SYSTEM_PROMPT), started.summary());
        assertFalse(started.summary().contains(TOOL_SCHEMA), started.summary());
        assertFalse(started.summary().contains(SESSION_JSONL), started.summary());
        assertFalse(started.summary().startsWith("{"), started.summary());
    }

    @Test
    void toolFinishedUsesOpaqueStorageRefIdOnlyAndNeverReplacementPath() throws Exception {
        UiAgentEventProjector projector = new UiAgentEventProjector();
        projector.project(new AgentEvent.ToolStartedEvent("turn-1", NOW, "tool-1", "run_command",
                JsonNodeFactory.instance.objectNode().put("command", "mvn").put("args", "test")));
        projector.project(new AgentEvent.AssistantMessageEvent("turn-1", NOW,
                new ToolResultMessage("tool-1", "run_command", "short result", false)));
        ToolResultReplacementRecord replacement = new ToolResultReplacementRecord(
                "tool-1",
                "run_command",
                ToolResultReplacementTrigger.SINGLE_RESULT_TOO_LARGE,
                new ToolResultStorageRef("opaque-storage-id", Path.of(STORAGE_PATH), 512),
                "<persisted-output>\nPATH: " + STORAGE_PATH + "\n" + SECRET_TOKEN + "\n</persisted-output>",
                "",
                512,
                0,
                120
        );

        UiEvent.ToolFinished finished = assertInstanceOf(UiEvent.ToolFinished.class, projector.project(
                new AgentEvent.ToolFinishedEvent("turn-1", NOW, "tool-1", "run_command", false, false,
                        Optional.of(replacement))).getFirst());
        String json = new UiEventEncoder().encode(finished);

        assertEquals("opaque-storage-id", finished.storageRef());
        assertFalse(json.contains(STORAGE_PATH), json);
        assertFalse(json.contains("PATH:"), json);
        assertFalse(json.contains(SECRET_TOKEN), json);
        assertFalse(json.contains("replacementContent"), json);
    }

    @Test
    void diffPreviewIsGeneratedTruncatedAndRedactedOnJavaSide() {
        String diff = String.join("\n",
                "--- secret.txt",
                "+++ secret.txt",
                "-old ANTHROPIC_AUTH_TOKEN=" + SECRET_TOKEN,
                "+new Authorization: Bearer " + SECRET_TOKEN,
                "+line 1",
                "+line 2",
                "+line 3",
                "+line 4");

        UiEvent.DiffPreview preview = UiDiffPreviewFactory.fromDiff("secret.txt", diff, 5);

        assertEquals("secret.txt", preview.title());
        assertTrue(preview.truncated());
        assertEquals(3, preview.hiddenLines());
        String rendered = String.join("\n", preview.lines());
        assertTrue(rendered.contains("<redacted>"), rendered);
        assertFalse(rendered.contains(SECRET_TOKEN), rendered);
    }

    @Test
    void historyProjectorFiltersSystemThinkingAndRawToolInput() {
        UiHistoryProjector projector = new UiHistoryProjector();
        ObjectNode rawThinking = JsonNodeFactory.instance.objectNode()
                .put("providerRawPayload", "raw-provider-payload-" + SECRET_TOKEN);
        List<SessionEvent> events = List.of(
                sessionMessage("event-system", new SystemMessage(SYSTEM_PROMPT)),
                sessionMessage("event-thinking", new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("thinking", rawThinking)))),
                sessionMessage("event-user", new UserMessage("hello")),
                sessionMessage("event-progress", new AssistantProgressMessage("checking")),
                sessionMessage("event-tool-call", new AssistantToolCallMessage("tool-1", "unknown_tool",
                        JsonNodeFactory.instance.objectNode().put("api_key", SECRET_TOKEN)))
        );

        List<UiEvent> projected = projector.project(events);
        String json = projected.stream().map(new UiEventEncoder()::encode).reduce("", String::concat);

        assertEquals(2, projected.size());
        assertTrue(projected.stream().anyMatch(event -> event instanceof UiEvent.HistoryItem item
                && item.kind().equals("user") && item.text().equals("hello")));
        assertTrue(projected.stream().anyMatch(event -> event instanceof UiEvent.HistoryItem item
                && item.kind().equals("progress") && item.text().equals("checking")));
        assertFalse(json.contains(SYSTEM_PROMPT), json);
        assertFalse(json.contains("providerRawPayload"), json);
        assertFalse(json.contains(SECRET_TOKEN), json);
        assertFalse(json.contains("tool_schema"), json);
    }

    @Test
    void contextStatsMapsSourcesAndCanBeUpdatedWithoutHistoryItems() {
        UiAgentEventProjector projector = new UiAgentEventProjector();

        List<UiEvent> first = projector.project(new AgentEvent.ContextStatsEvent("turn-1", NOW,
                stats(TokenAccountingResult.estimateOnly(100, Optional.of("usage missing")), 1_000, 0.10d)));
        List<UiEvent> second = projector.project(new AgentEvent.ContextStatsEvent("turn-1", NOW,
                stats(TokenAccountingResult.providerUsageWithEstimate(200, 20, 260, 180, 80,
                        new UsageBoundary(0, Optional.of("message-1"))), 1_000, 0.26d)));
        List<UiEvent> third = projector.project(new AgentEvent.ContextStatsEvent("turn-1", NOW,
                stats(TokenAccountingResult.providerUsage(300, 30, 330,
                        new UsageBoundary(1, Optional.of("message-2"))), 1_000, 0.33d)));

        assertEquals("estimate", assertInstanceOf(UiEvent.ContextStats.class, first.getFirst()).source());
        assertEquals("provider+estimate", assertInstanceOf(UiEvent.ContextStats.class, second.getFirst()).source());
        assertEquals("provider", assertInstanceOf(UiEvent.ContextStats.class, third.getFirst()).source());
        assertTrue(first.stream().noneMatch(UiEvent.HistoryItem.class::isInstance));
        assertTrue(second.stream().noneMatch(UiEvent.HistoryItem.class::isInstance));
        assertTrue(third.stream().noneMatch(UiEvent.HistoryItem.class::isInstance));
    }

    @Test
    void permissionProjectionRedactsAndBoundsEditFacts() {
        UiPermissionBridge bridge = new UiPermissionBridge();
        PermissionRequest request = editPermissionRequest();

        UiEvent.PermissionRequest event = bridge.start(request);
        String json = new UiEventEncoder().encode(event);

        assertEquals("edit-request-1", event.requestId());
        assertTrue(event.facts().stream().anyMatch(fact -> fact.contains("Diff preview")));
        assertTrue(event.facts().stream().anyMatch(fact -> fact.contains("hidden diff lines")));
        assertFalse(json.contains(SECRET_TOKEN), json);
        assertFalse(json.contains(Path.of(STORAGE_PATH).toString()), json);
        assertFalse(event.facts().toString().contains("+line 20"), event.facts().toString());
    }

    private static UiEvent.DiffPreview diffPreview() {
        return new UiEvent.DiffPreview("README.md", List.of("--- README.md", "+++ README.md"),
                true, 1);
    }

    private static UiEvent.ContextStats contextStats(TokenAccountingResult accounting) {
        return UiEvent.ContextStats.from(stats(accounting, 1_000, 0.012d));
    }

    private static ContextStats stats(TokenAccountingResult accounting, long effectiveInput, double utilization) {
        return new ContextStats(accounting, effectiveInput + 100, 100, effectiveInput, utilization,
                ContextWarningLevel.NORMAL);
    }

    private static SessionEvent sessionMessage(String uuid, minicode.core.message.ChatMessage message) {
        return SessionEvent.message(uuid, NOW, "session-1", "E:\\work", Optional.empty(), Optional.empty(), message);
    }

    private static PermissionRequest editPermissionRequest() {
        StringBuilder diff = new StringBuilder();
        diff.append("--- secret.txt\n+++ secret.txt\n");
        for (int index = 0; index < 20; index++) {
            diff.append("+line ").append(index).append(" ANTHROPIC_AUTH_TOKEN=").append(SECRET_TOKEN).append('\n');
        }
        EditReview review = new EditReview(
                Path.of("secret.txt"),
                PermissionResource.EditOperation.EDIT,
                "replace token",
                diff.toString(),
                10,
                20,
                true,
                true,
                "fingerprint-1",
                Optional.of(STORAGE_PATH)
        );
        return new PermissionRequest(
                "edit-request-1",
                PermissionRequestKind.EDIT,
                new PermissionResource.EditResource(review, Optional.of("tool-edit-1")),
                "Allow file edit",
                new PermissionRequestDetails("Edit review", "Review the proposed file change.", List.of()),
                List.of(
                        PermissionChoice.allowOnce("allow_once", "Allow this edit"),
                        PermissionChoice.denyOnce("deny_once", "Deny"),
                        PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
                ),
                true,
                PermissionScope.ONCE,
                new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-edit-1"))
        );
    }

    private static Set<String> iterableToSet(java.util.Iterator<String> iterator) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        iterator.forEachRemaining(result::add);
        return result;
    }
}
