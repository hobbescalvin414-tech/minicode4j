package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.context.accounting.TokenAccountingService;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.message.*;
import minicode.core.turn.*;
import minicode.model.ModelRequestException;
import minicode.model.ProviderUsage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.service.SessionService;
import minicode.session.store.SessionStore;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicAgentLoopSemanticsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void fixtureRunsToolUseToolResultContinueFinalWithUsageAndContextStats() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceTransport transport = new SequenceTransport(
                ok("""
                        {
                          "stop_reason": "tool_use",
                          "content": [
                            {"type": "text", "text": "<progress>checking echo</progress>"},
                            {"type": "tool_use", "id": "tool-1", "name": "echo_fixture", "input": {"text": "hello"}}
                          ],
                          "usage": {"input_tokens": 10, "output_tokens": 5}
                        }
                        """),
                ok("""
                        {
                          "stop_reason": "end_turn",
                          "content": [
                            {"type": "text", "text": "<final>echo complete</final>"}
                          ],
                          "usage": {"input_tokens": 30, "output_tokens": 7}
                        }
                        """)
        );
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                new AnthropicModelAdapter(config(), registry, transport, 0),
                eventSink,
                registry,
                minicode.context.manager.ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(160, 40))
        );

        AgentTurnResult result = loop.runTurn(request(List.of(new SystemMessage("sys"), new UserMessage("use tool"))));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(2, transport.calls);
        assertInstanceOf(AssistantProgressMessage.class, result.messages().get(2));
        assertInstanceOf(UserMessage.class, result.messages().get(3));
        AssistantToolCallMessage toolCall = assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(4));
        assertEquals(new ProviderUsage(10, 5, 15), toolCall.providerUsage().orElseThrow());
        ToolResultMessage toolResult = assertInstanceOf(ToolResultMessage.class, result.messages().get(5));
        assertEquals("echo: hello", toolResult.content());
        AssistantMessage finalMessage = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("echo complete", finalMessage.content());
        assertEquals(new ProviderUsage(30, 7, 37), finalMessage.providerUsage().orElseThrow());

        List<AgentEvent.ContextStatsEvent> statsEvents = eventSink.events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .toList();
        assertEquals(2, statsEvents.size());
        assertEquals(160, statsEvents.getFirst().stats().contextWindow());
        assertEquals(40, statsEvents.getFirst().stats().outputReserve());
        assertEquals(120, statsEvents.getFirst().stats().effectiveInput());
    }

    @Test
    void fixtureGroupsMultipleToolUsesThenMultipleToolResultsForProviderRequest() {
        ToolRegistry registry = registry(new EchoTool(false));
        RecordingSequenceTransport transport = new RecordingSequenceTransport(
                ok("""
                        {
                          "stop_reason": "tool_use",
                          "content": [
                            {"type": "tool_use", "id": "tool-1", "name": "echo_fixture", "input": {"text": "one"}},
                            {"type": "tool_use", "id": "tool-2", "name": "echo_fixture", "input": {"text": "two"}}
                          ],
                          "usage": {"input_tokens": 8, "output_tokens": 4}
                        }
                        """),
                ok("""
                        {
                          "stop_reason": "end_turn",
                          "content": [
                            {"type": "text", "text": "<final>both complete</final>"}
                          ],
                          "usage": {"input_tokens": 20, "output_tokens": 5}
                        }
                        """)
        );
        AgentLoop loop = new AgentLoop(new AnthropicModelAdapter(config(), registry, transport, 0),
                AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("use two tools"))));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        JsonNode providerMessages = transport.requestBodies.get(1).get("messages");
        assertEquals("user", providerMessages.get(0).get("role").asText());
        assertEquals("assistant", providerMessages.get(1).get("role").asText());
        assertEquals("tool_use", providerMessages.get(1).get("content").get(0).get("type").asText());
        assertEquals("tool_use", providerMessages.get(1).get("content").get(1).get("type").asText());
        assertEquals("tool-1", providerMessages.get(1).get("content").get(0).get("id").asText());
        assertEquals("tool-2", providerMessages.get(1).get("content").get(1).get("id").asText());
        assertEquals("user", providerMessages.get(2).get("role").asText());
        assertEquals("tool_result", providerMessages.get(2).get("content").get(0).get("type").asText());
        assertEquals("tool_result", providerMessages.get(2).get("content").get(1).get("type").asText());
        assertEquals("tool-1", providerMessages.get(2).get("content").get(0).get("tool_use_id").asText());
        assertEquals("tool-2", providerMessages.get(2).get("content").get(1).get("tool_use_id").asText());
    }

    @Test
    void fixturePreservesMultiToolHistoryShapeAcrossSessionResumeRoundTrip() {
        SessionStore store = new SessionStore(tempDir);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store,
                new SessionEventFactory("session-1", "E:/work"));
        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(
                        new UserMessage("use two tools"),
                        new AssistantToolCallMessage("tool-1", "echo_fixture",
                                JsonNodeFactory.instance.objectNode().put("text", "one"),
                                Optional.of(new ProviderUsage(8, 4, 12)),
                                minicode.model.UsageStaleness.fresh()),
                        new AssistantToolCallMessage("tool-2", "echo_fixture",
                                JsonNodeFactory.instance.objectNode().put("text", "two")),
                        new ToolResultMessage("tool-1", "echo_fixture", "echo: one", false),
                        new ToolResultMessage("tool-2", "echo_fixture", "echo: two", false)
                ))
        )));
        List<ChatMessage> resumedMessages = new SessionService(store).resumeMessages("E:/work", "session-1");
        ToolRegistry registry = registry(new EchoTool(false));
        RecordingSequenceTransport transport = new RecordingSequenceTransport(ok("""
                {
                  "stop_reason": "end_turn",
                  "content": [
                    {"type": "text", "text": "<final>resumed</final>"}
                  ]
                }
                """));
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry, transport, 0);

        adapter.next(resumedMessages);

        JsonNode providerMessages = transport.requestBodies.getFirst().get("messages");
        assertEquals("user", providerMessages.get(0).get("role").asText());
        assertEquals("assistant", providerMessages.get(1).get("role").asText());
        assertEquals("tool_use", providerMessages.get(1).get("content").get(0).get("type").asText());
        assertEquals("tool_use", providerMessages.get(1).get("content").get(1).get("type").asText());
        assertEquals("tool-1", providerMessages.get(1).get("content").get(0).get("id").asText());
        assertEquals("tool-2", providerMessages.get(1).get("content").get(1).get("id").asText());
        assertEquals("user", providerMessages.get(2).get("role").asText());
        assertEquals("tool_result", providerMessages.get(2).get("content").get(0).get("type").asText());
        assertEquals("tool_result", providerMessages.get(2).get("content").get(1).get("type").asText());
        assertEquals("tool-1", providerMessages.get(2).get("content").get(0).get("tool_use_id").asText());
        assertEquals("tool-2", providerMessages.get(2).get("content").get(1).get("tool_use_id").asText());
    }

    @Test
    void fixtureUsesAgentLoopEmptyResponseFallbackAfterRecentToolError() {
        ToolRegistry registry = registry(new EchoTool(true));
        SequenceTransport transport = new SequenceTransport(
                ok("""
                        {
                          "stop_reason": "tool_use",
                          "content": [
                            {"type": "tool_use", "id": "tool-1", "name": "echo_fixture", "input": {"text": "fail"}}
                          ],
                          "usage": {"input_tokens": 3, "output_tokens": 2}
                        }
                        """),
                ok("{\"stop_reason\": \"end_turn\", \"content\": []}"),
                ok("{\"stop_reason\": \"end_turn\", \"content\": []}"),
                ok("{\"stop_reason\": \"end_turn\", \"content\": []}")
        );
        AgentLoop loop = new AgentLoop(new AnthropicModelAdapter(config(), registry, transport, 0),
                AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("use failing tool"))));

        assertEquals(AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, result.stopReason());
        EmptyFallbackDetails details = assertInstanceOf(EmptyFallbackDetails.class, result.stopDetails().orElseThrow());
        assertEquals(Optional.of("empty_after_tool_error"), details.reason());
        assertEquals(1, details.toolErrorCount());
        assertTrue(result.messages().stream().anyMatch(message ->
                message instanceof UserMessage user
                        && user.content().contains("recent tool results that included errors")));
        AssistantMessage fallback = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertTrue(fallback.content().contains("tool error"));
    }

    @Test
    void fixtureRecoversPauseTurnAndMaxTokensThinkingStops() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceTransport transport = new SequenceTransport(
                ok("""
                        {
                          "stop_reason": "pause_turn",
                          "content": [
                            {"type": "thinking", "thinking": "need more time"}
                          ]
                        }
                        """),
                ok("""
                        {
                          "stop_reason": "max_tokens",
                          "content": [
                            {"type": "redacted_thinking", "data": "opaque"}
                          ]
                        }
                        """),
                ok("""
                        {
                          "stop_reason": "end_turn",
                          "content": [
                            {"type": "text", "text": "<final>recovered</final>"}
                          ],
                          "usage": {"input_tokens": 12, "output_tokens": 4}
                        }
                        """)
        );
        AgentLoop loop = new AgentLoop(new AnthropicModelAdapter(config(), registry, transport, 0),
                AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("think then finish"))));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(3, transport.calls);
        List<AssistantProgressMessage> progressMessages = result.messages().stream()
                .filter(AssistantProgressMessage.class::isInstance)
                .map(AssistantProgressMessage.class::cast)
                .toList();
        assertEquals(2, progressMessages.size());
        assertTrue(progressMessages.get(0).content().contains("pause_turn"));
        assertTrue(progressMessages.get(1).content().contains("max_tokens"));
        List<UserMessage> continuationPrompts = result.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(message -> message.content().contains("Resume")
                        || message.content().contains("hit max_tokens"))
                .toList();
        assertEquals(2, continuationPrompts.size());
        AssistantMessage finalMessage = assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("recovered", finalMessage.content());
    }

    @Test
    void fixtureReturnsProviderNeutralModelErrorWithoutAssistantFinal() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceTransport transport = new SequenceTransport(
                new AnthropicTransport.Response(429, Map.of(), """
                        {"error":{"message":"rate limited"}}
                        """)
        );
        AgentLoop loop = new AgentLoop(new AnthropicModelAdapter(config(), registry, transport, 0),
                AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi"))));

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(1, result.messages().size());
        assertFalse(result.messages().stream().anyMatch(AssistantMessage.class::isInstance));
        ModelErrorDetails details = assertInstanceOf(ModelErrorDetails.class, result.stopDetails().orElseThrow());
        assertTrue(details.error().retryable());
        assertEquals(TurnErrorSource.MODEL, details.error().source());
        assertEquals(Optional.of(ModelRequestException.class.getName()), details.error().causeClass());
        assertTrue(details.error().diagnostics().orElseThrow().contains("statusCode=429"));
    }

    private static AnthropicTransport.Response ok(String body) {
        return new AnthropicTransport.Response(200, Map.of(), body);
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "claude-test",
                "https://anthropic.example",
                Optional.of("test-key"),
                Optional.empty(),
                Optional.of(1024),
                Optional.of(160),
                "test"
        );
    }

    private static AgentTurnRequest request(List<ChatMessage> messages) {
        return new AgentTurnRequest(
                "turn-1",
                Path.of("E:/Minicode-Java/workspace"),
                "session-1",
                messages,
                8,
                Optional.empty()
        );
    }

    private static ToolRegistry registry(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private static final class EchoTool implements Tool {
        private final boolean fail;

        private EchoTool(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "echo_fixture",
                    "Echoes the text field. Test fixture for provider/agent loop semantics.",
                    inputSchema(),
                    ToolOrigin.BUILTIN,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE
            );
        }

        @Override
        public JsonNode inputSchema() {
            return JsonNodeFactory.instance.objectNode()
                    .put("type", "object")
                    .set("properties", JsonNodeFactory.instance.objectNode()
                            .set("text", JsonNodeFactory.instance.objectNode().put("type", "string")));
        }

        @Override
        public ValidationResult validateInput(JsonNode input) {
            return ValidationResult.valid(input == null || input.isMissingNode()
                    ? JsonNodeFactory.instance.objectNode()
                    : input);
        }

        @Override
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            if (fail) {
                return ToolResult.error("fixture failure");
            }
            return ToolResult.ok("echo: " + normalizedInput.path("text").asText(""));
        }
    }

    private static final class SequenceTransport implements AnthropicTransport {
        private final List<AnthropicTransport.Response> responses;
        private int calls;

        private SequenceTransport(AnthropicTransport.Response... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public AnthropicTransport.Response post(String url, Map<String, String> headers, JsonNode requestBody) {
            calls++;
            return responses.get(Math.min(calls - 1, responses.size() - 1));
        }
    }

    private static final class RecordingSequenceTransport implements AnthropicTransport {
        private final List<AnthropicTransport.Response> responses;
        private final List<JsonNode> requestBodies = new ArrayList<>();
        private int calls;

        private RecordingSequenceTransport(AnthropicTransport.Response... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public AnthropicTransport.Response post(String url, Map<String, String> headers, JsonNode requestBody) {
            requestBodies.add(requestBody.deepCopy());
            calls++;
            return responses.get(Math.min(calls - 1, responses.size() - 1));
        }
    }

    private static final class RecordingEventSink implements AgentEventSink {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
        }
    }
}
