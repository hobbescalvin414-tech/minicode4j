package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.message.*;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.model.ProviderThinkingBlock;
import minicode.tools.api.ToolCall;
import minicode.tools.builtin.AskUserTool;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicModelAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void convertsAssistantTextThinkingAndUsageToProviderNeutralStep() {
        RecordingTransport transport = new RecordingTransport(200, """
                {
                  "stop_reason": "end_turn",
                  "content": [
                    {"type": "thinking", "thinking": "checked the repo"},
                    {"type": "text", "text": "<final>done</final>"}
                  ],
                  "usage": {
                    "input_tokens": 10,
                    "cache_creation_input_tokens": 2,
                    "cache_read_input_tokens": 3,
                    "output_tokens": 5
                  }
                }
                """);
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport);

        AssistantStep step = assertInstanceOf(AssistantStep.class,
                adapter.next(List.of(new SystemMessage("sys"), new UserMessage("hi"))));

        assertEquals(AssistantKind.FINAL, step.kind());
        assertEquals("done", step.content());
        assertEquals(1, step.thinkingBlocks().size());
        ProviderThinkingBlock thinking = step.thinkingBlocks().getFirst();
        assertEquals("thinking", thinking.type());
        assertEquals("checked the repo", thinking.raw().get("thinking").asText());
        assertEquals(15, step.usage().orElseThrow().inputTokens());
        assertEquals(5, step.usage().orElseThrow().outputTokens());
        assertEquals(20, step.usage().orElseThrow().totalTokens());
        assertEquals(Optional.of("end_turn"), step.diagnostics().orElseThrow().stopReason());
        assertTrue(transport.lastBody.get("tools").isArray());
        assertEquals("ask_user", transport.lastBody.get("tools").get(0).get("name").asText());
    }

    @Test
    void convertsToolUseBlocksAndProgressTextToToolCallsStep() {
        RecordingTransport transport = new RecordingTransport(200, """
                {
                  "stop_reason": "tool_use",
                  "content": [
                    {"type": "text", "text": "<progress>Need a file read</progress>"},
                    {"type": "tool_use", "id": "tool-1", "name": "ask_user", "input": {"question": "Continue?"}},
                    {"type": "unknown_block", "value": true}
                  ],
                  "usage": {"input_tokens": 4, "output_tokens": 6}
                }
                """);
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport);

        ToolCallsStep step = assertInstanceOf(ToolCallsStep.class,
                adapter.next(List.of(new UserMessage("hi"))));

        assertEquals(Optional.of("Need a file read"), step.content());
        assertEquals(ContentKind.PROGRESS, step.contentKind());
        ToolCall call = step.calls().getFirst();
        assertEquals("tool-1", call.id());
        assertEquals("ask_user", call.toolName());
        assertEquals("Continue?", call.input().get("question").asText());
        assertEquals(List.of("unknown_block"), step.diagnostics().orElseThrow().ignoredBlockTypes());
        assertEquals(10, step.usage().orElseThrow().totalTokens());
    }

    @Test
    void translatesMessagesToAnthropicShapeWithoutLeakingPayloadToCore() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}
                """);
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport);

        adapter.next(List.of(
                new SystemMessage("system one"),
                new AssistantMessage("prior answer"),
                new AssistantToolCallMessage("tool-1", "ask_user",
                        JsonNodeFactory.instance.objectNode().put("question", "Q?")),
                new ToolResultMessage("tool-1", "ask_user", "A", false)
        ));

        assertEquals("system one", transport.lastBody.get("system").asText());
        JsonNode messages = transport.lastBody.get("messages");
        assertEquals("assistant", messages.get(0).get("role").asText());
        assertEquals("text", messages.get(0).get("content").get(0).get("type").asText());
        assertEquals("tool_use", messages.get(0).get("content").get(1).get("type").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("tool_result", messages.get(1).get("content").get(0).get("type").asText());
    }

    @Test
    void providerErrorIsNormalized() {
        RecordingTransport transport = new RecordingTransport(429, """
                {"error":{"message":"rate limited"}}
                """);
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport, 0);

        ProviderRequestException exception = assertThrows(ProviderRequestException.class,
                () -> adapter.next(List.of(new UserMessage("hi"))));

        assertEquals(429, exception.statusCode().orElseThrow());
        assertTrue(exception.getMessage().contains("rate limited"));
        assertTrue(exception.retryable());
    }

    @Test
    void retryableProviderErrorsAreRetriedBeforeReturningStep() {
        SequenceTransport transport = new SequenceTransport(
                new AnthropicTransport.Response(429, Map.of(), "{\"error\":{\"message\":\"rate limited\"}}"),
                new AnthropicTransport.Response(200, Map.of(),
                        "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
        );
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport, 1, delayStrategy);

        AssistantStep step = assertInstanceOf(AssistantStep.class, adapter.next(List.of(new UserMessage("hi"))));

        assertEquals("ok", step.content());
        assertEquals(2, transport.calls);
        assertEquals(1, delayStrategy.delays.size());
    }

    @Test
    void emptyProviderContentReturnsBlankAssistantStepForAgentLoopFallback() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"stop_reason":"end_turn","content":[]}
                """);
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport);

        AssistantStep step = assertInstanceOf(AssistantStep.class, adapter.next(List.of(new UserMessage("hi"))));

        assertEquals("", step.content());
        assertEquals(AssistantKind.UNSPECIFIED, step.kind());
    }

    @Test
    void emptyProviderContentUsesAgentLoopEmptyFallbackPath() {
        SequenceTransport transport = new SequenceTransport(
                new AnthropicTransport.Response(200, Map.of(), "{\"stop_reason\":\"end_turn\",\"content\":[]}"),
                new AnthropicTransport.Response(200, Map.of(), "{\"stop_reason\":\"end_turn\",\"content\":[]}")
        );
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport);
        AgentLoop loop = new AgentLoop(adapter, AgentEventSink.noOp(), 1);

        AgentTurnResult result = loop.runTurn(new AgentTurnRequest(
                "turn-1",
                java.nio.file.Path.of("E:/Minicode-Java/workspace"),
                "session-1",
                List.of(new UserMessage("hi")),
                3,
                Optional.empty()
        ));

        assertEquals(AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, result.stopReason());
        assertTrue(result.messages().stream().anyMatch(message ->
                message instanceof UserMessage user && user.content().contains("Your last response was empty")));
    }

    @Test
    void retryAfterHeaderControlsBackoffDelay() {
        SequenceTransport transport = new SequenceTransport(
                new AnthropicTransport.Response(429, Map.of("retry-after", List.of("2")),
                        "{\"error\":{\"message\":\"rate limited\"}}"),
                new AnthropicTransport.Response(200, Map.of(),
                        "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")
        );
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config(), registry(), transport, 1, delayStrategy);

        adapter.next(List.of(new UserMessage("hi")));

        assertEquals(List.of(2000L), delayStrategy.delays);
    }

    @Test
    void requestMaxTokensUsesModelDefaultWhenUnconfigured() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}
                """);
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "claude-3-5-haiku-latest",
                "https://anthropic.example",
                Optional.of("key"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(200000),
                "test"
        );
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config, registry(), transport);

        adapter.next(List.of(new UserMessage("hi")));

        assertEquals(8192, transport.lastBody.get("max_tokens").asInt());
    }

    @Test
    void requestMaxTokensClampsConfiguredValueToModelLimit() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}
                """);
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "claude-3-5-haiku-latest",
                "https://anthropic.example",
                Optional.of("key"),
                Optional.empty(),
                Optional.of(100000),
                Optional.of(200000),
                "test"
        );
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config, registry(), transport);

        adapter.next(List.of(new UserMessage("hi")));

        assertEquals(8192, transport.lastBody.get("max_tokens").asInt());
    }

    @Test
    void requestMaxTokensCanUseResolvedProfileValueFromMetadata() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}
                """);
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "custom-metadata-model",
                "https://anthropic.example",
                Optional.of("key"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "test"
        );
        AnthropicModelAdapter adapter = new AnthropicModelAdapter(config, registry(), transport, Optional.of(64_000));

        adapter.next(List.of(new UserMessage("hi")));

        assertEquals(64_000, transport.lastBody.get("max_tokens").asInt());
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "claude-test",
                "https://anthropic.example",
                Optional.of("key"),
                Optional.empty(),
                Optional.of(4096),
                Optional.of(200000),
                "test"
        );
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new AskUserTool());
        return registry;
    }

    private static final class RecordingTransport implements AnthropicTransport {
        private final int status;
        private final String body;
        private JsonNode lastBody;

        private RecordingTransport(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public AnthropicTransport.Response post(String url, Map<String, String> headers, JsonNode requestBody) {
            this.lastBody = requestBody;
            return new AnthropicTransport.Response(status, Map.of(), body);
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
            return responses.get(Math.min(calls++, responses.size() - 1));
        }
    }

    private static final class RecordingDelayStrategy implements AnthropicModelAdapter.RetryDelayStrategy {
        private final java.util.ArrayList<Long> delays = new java.util.ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }
}
