package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnthropicProviderSmokeTest {
    @Test
    void realAnthropicProviderCanDriveToolUseToolResultContinueFinal() {
        String enabled = System.getenv("MINICODE_ANTHROPIC_SMOKE");
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
        assumeTrue("1".equals(enabled), "Set MINICODE_ANTHROPIC_SMOKE=1 to run the real Anthropic smoke test.");
        assumeTrue((authToken != null && !authToken.isBlank()) || (apiKey != null && !apiKey.isBlank()),
                "Set ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY to run the real Anthropic smoke test.");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        RuntimeConfig config = new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                envOrDefault("ANTHROPIC_MODEL", "claude-3-5-haiku-latest"),
                envOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com"),
                optionalEnv("ANTHROPIC_API_KEY"),
                optionalEnv("ANTHROPIC_AUTH_TOKEN"),
                Optional.of(Integer.parseInt(envOrDefault("MINICODE_ANTHROPIC_SMOKE_MAX_TOKENS",
                        envOrDefault("MINICODE_MAX_OUTPUT_TOKENS", "512")))),
                Optional.empty(),
                "smoke"
        );
        AgentLoop loop = new AgentLoop(new AnthropicModelAdapter(config, registry), AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(new AgentTurnRequest(
                "anthropic-smoke-turn",
                Path.of(System.getProperty("user.dir")),
                "anthropic-smoke-session",
                List.of(
                        new SystemMessage("""
                                You are running a MiniCode provider smoke test.
                                You must first call echo_fixture once with {"text":"anthropic-smoke"}.
                                After receiving the tool result, answer exactly with <final>smoke complete</final>.
                                Do not ask the user questions.
                                """),
                        new UserMessage("Run the smoke path now.")
                ),
                6,
                Optional.empty()
        ));

        if (result.stopReason() == AgentTurnStopReason.MODEL_ERROR
                && result.stopDetails().map(Object::toString).orElse("").contains("statusCode=402")) {
            assumeTrue(false, "Skipping real provider smoke because the configured account has insufficient balance.");
        }
        assertEquals(AgentTurnStopReason.FINAL, result.stopReason(), () -> "stopDetails=" + result.stopDetails());
        assertTrue(result.messages().stream().anyMatch(message -> message instanceof AssistantMessage assistant
                && assistant.content().contains("smoke complete")));
        assertTrue(result.messages().stream()
                .filter(ChatMessage.class::isInstance)
                .anyMatch(message -> message instanceof minicode.core.message.ToolResultMessage toolResult
                        && toolResult.content().contains("echo: anthropic-smoke")));
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Optional<String> optionalEnv(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static final class EchoTool implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "echo_fixture",
                    "Echoes the text field for a provider smoke test.",
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
            return ToolResult.ok("echo: " + normalizedInput.path("text").asText(""));
        }
    }
}
