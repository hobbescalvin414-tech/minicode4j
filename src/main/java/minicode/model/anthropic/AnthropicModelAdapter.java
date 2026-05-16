package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.config.RuntimeConfig;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.*;
import minicode.core.step.*;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;
import minicode.model.ModelLimits;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.registry.ToolRegistry;

import java.util.*;

public final class AnthropicModelAdapter implements ModelAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BASE_RETRY_DELAY_MS = 500L;
    private static final long MAX_RETRY_DELAY_MS = 8_000L;

    private final RuntimeConfig runtimeConfig;
    private final ToolRegistry tools;
    private final AnthropicTransport transport;
    private final Optional<Integer> resolvedMaxOutputTokens;
    private final int maxRetries;
    private final RetryDelayStrategy retryDelayStrategy;

    public interface RetryDelayStrategy {
        void sleep(long millis);

        static RetryDelayStrategy threadSleep() {
            return millis -> {
                try {
                    Thread.sleep(Math.max(0L, millis));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ProviderRequestException("Provider retry sleep interrupted",
                            Optional.empty(), true, exception);
                }
            };
        }
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools) {
        this(runtimeConfig, tools, new HttpAnthropicTransport());
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport) {
        this(runtimeConfig, tools, transport, 2);
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 Optional<Integer> resolvedMaxOutputTokens) {
        this(runtimeConfig, tools, transport, resolvedMaxOutputTokens, 2);
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 int maxRetries) {
        this(runtimeConfig, tools, transport, Optional.empty(), maxRetries, RetryDelayStrategy.threadSleep());
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 Optional<Integer> resolvedMaxOutputTokens, int maxRetries) {
        this(runtimeConfig, tools, transport, resolvedMaxOutputTokens, maxRetries, RetryDelayStrategy.threadSleep());
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 int maxRetries, RetryDelayStrategy retryDelayStrategy) {
        this(runtimeConfig, tools, transport, Optional.empty(), maxRetries, retryDelayStrategy);
    }

    public AnthropicModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, AnthropicTransport transport,
                                 Optional<Integer> resolvedMaxOutputTokens, int maxRetries,
                                 RetryDelayStrategy retryDelayStrategy) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.resolvedMaxOutputTokens = Objects.requireNonNull(resolvedMaxOutputTokens, "resolvedMaxOutputTokens")
                .filter(value -> value > 0);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
        this.retryDelayStrategy = Objects.requireNonNull(retryDelayStrategy, "retryDelayStrategy");
    }

    @Override
    public AgentStep next(List<ChatMessage> messages) {
        JsonNode requestBody = requestBody(messages);
        AnthropicTransport.Response response = sendWithRetries(requestBody);
        JsonNode data = parseBody(response.body());
        if (!response.ok()) {
            throw new ProviderRequestException(extractErrorMessage(data, response.statusCode()),
                    Optional.of(response.statusCode()), shouldRetryStatus(response.statusCode()));
        }
        return parseStep(data);
    }

    private AnthropicTransport.Response sendWithRetries(JsonNode requestBody) {
        String url = runtimeConfig.baseUrl().replaceAll("/+$", "") + "/v1/messages";
        Map<String, String> actualHeaders = headers();
        ProviderRequestException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                AnthropicTransport.Response response = transport.post(url, actualHeaders, requestBody);
                if (response.ok() || !shouldRetryStatus(response.statusCode()) || attempt >= maxRetries) {
                    return response;
                }
                retryDelayStrategy.sleep(retryDelayMs(response, attempt + 1));
            } catch (ProviderRequestException exception) {
                lastException = exception;
                if (!exception.retryable() || attempt >= maxRetries) {
                    throw exception;
                }
                retryDelayStrategy.sleep(retryDelayMs(null, attempt + 1));
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new ProviderRequestException("Provider request failed before receiving a response");
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("anthropic-version", "2023-06-01");
        runtimeConfig.authToken().ifPresent(token -> headers.put("Authorization", "Bearer " + token));
        if (runtimeConfig.authToken().isEmpty()) {
            runtimeConfig.apiKey().ifPresent(key -> headers.put("x-api-key", key));
        }
        return headers;
    }

    private JsonNode requestBody(List<ChatMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", runtimeConfig.model());
        root.put("system", systemText(messages));
        root.set("messages", toProviderMessages(messages));
        root.set("tools", toolSchemas());
        root.put("max_tokens", resolvedMaxOutputTokens.orElseGet(() ->
                ModelLimits.resolveMaxOutputTokens(runtimeConfig.model(), runtimeConfig.maxOutputTokens())));
        return root;
    }

    private String systemText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private ArrayNode toProviderMessages(List<ChatMessage> messages) {
        ArrayNode converted = MAPPER.createArrayNode();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof UserMessage user) {
                pushBlock(converted, "user", textBlock(user.content()));
            } else if (message instanceof ContextSummaryMessage summary) {
                pushBlock(converted, "user", textBlock("[Context Summary from earlier conversation]\n" + summary.content()));
            } else if (message instanceof AssistantThinkingMessage thinking) {
                for (ProviderThinkingBlock block : thinking.blocks()) {
                    pushBlock(converted, "assistant", block.raw());
                }
            } else if (message instanceof AssistantProgressMessage progress) {
                pushBlock(converted, "assistant", textBlock("<progress>\n" + progress.content() + "\n</progress>"));
            } else if (message instanceof AssistantMessage assistant) {
                pushBlock(converted, "assistant", textBlock(assistant.content()));
            } else if (message instanceof AssistantToolCallMessage toolCall) {
                ObjectNode block = MAPPER.createObjectNode();
                block.put("type", "tool_use");
                block.put("id", toolCall.toolUseId());
                block.put("name", toolCall.toolName());
                block.set("input", toolCall.input());
                pushBlock(converted, "assistant", block);
            } else if (message instanceof ToolResultMessage result) {
                ObjectNode block = MAPPER.createObjectNode();
                block.put("type", "tool_result");
                block.put("tool_use_id", result.toolUseId());
                block.put("content", result.content());
                block.put("is_error", result.error());
                pushBlock(converted, "user", block);
            }
        }
        return converted;
    }

    private void pushBlock(ArrayNode messages, String role, JsonNode block) {
        if (!messages.isEmpty() && role.equals(messages.get(messages.size() - 1).get("role").asText())) {
            ((ArrayNode) messages.get(messages.size() - 1).get("content")).add(block);
            return;
        }
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", role);
        message.set("content", MAPPER.createArrayNode().add(block));
        messages.add(message);
    }

    private ObjectNode textBlock(String text) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private ArrayNode toolSchemas() {
        ArrayNode schemas = MAPPER.createArrayNode();
        for (Tool tool : tools.list()) {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("name", tool.metadata().name());
            schema.put("description", tool.metadata().description());
            schema.set("input_schema", tool.inputSchema());
            schemas.add(schema);
        }
        return schemas;
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception exception) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.putObject("error").put("message", body.trim());
            return fallback;
        }
    }

    private AgentStep parseStep(JsonNode data) {
        JsonNode content = data.get("content");
        List<ToolCall> toolCalls = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        List<ProviderThinkingBlock> thinkingBlocks = new ArrayList<>();
        List<String> blockTypes = new ArrayList<>();
        LinkedHashSet<String> ignoredBlockTypes = new LinkedHashSet<>();

        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
            String type = block.path("type").asText("");
            blockTypes.add(type);
            switch (type) {
                case "text" -> textParts.add(block.path("text").asText(""));
                case "tool_use" -> toolCalls.add(new ToolCall(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        block.path("input").isMissingNode() ? MAPPER.createObjectNode() : block.path("input")
                ));
                case "thinking", "redacted_thinking" -> thinkingBlocks.add(new ProviderThinkingBlock(type, block));
                default -> ignoredBlockTypes.add(type);
            }
            }
        }

        ParsedText parsedText = parseAssistantText(String.join("\n", textParts).trim());
        StepDiagnostics diagnostics = new StepDiagnostics(
                optionalText(data.path("stop_reason").asText("")),
                blockTypes,
                List.copyOf(ignoredBlockTypes)
        );
        Optional<ProviderUsage> usage = normalizeUsage(data.get("usage"));
        if (!toolCalls.isEmpty()) {
            return new ToolCallsStep(
                    toolCalls,
                    optionalText(parsedText.content()),
                    parsedText.kind() == AssistantKind.PROGRESS ? ContentKind.PROGRESS : ContentKind.UNSPECIFIED,
                    thinkingBlocks,
                    Optional.of(diagnostics),
                    usage
            );
        }
        return new AssistantStep(parsedText.content(), parsedText.kind(), thinkingBlocks, Optional.of(diagnostics), usage);
    }

    private ParsedText parseAssistantText(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("<final>")) {
            return new ParsedText(trimmed.substring("<final>".length()).replaceAll("(?i)</final>", "").trim(),
                    AssistantKind.FINAL);
        }
        if (trimmed.startsWith("[FINAL]")) {
            return new ParsedText(trimmed.substring("[FINAL]".length()).trim(), AssistantKind.FINAL);
        }
        if (trimmed.startsWith("<progress>")) {
            return new ParsedText(trimmed.substring("<progress>".length()).replaceAll("(?i)</progress>", "").trim(),
                    AssistantKind.PROGRESS);
        }
        if (trimmed.startsWith("[PROGRESS]")) {
            return new ParsedText(trimmed.substring("[PROGRESS]".length()).trim(), AssistantKind.PROGRESS);
        }
        return new ParsedText(trimmed, AssistantKind.UNSPECIFIED);
    }

    private Optional<ProviderUsage> normalizeUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        int input = usage.path("input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int total = input + output;
        return total <= 0 ? Optional.empty() : Optional.of(new ProviderUsage(input, output, total));
    }

    private Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private String extractErrorMessage(JsonNode data, int status) {
        String nested = data.path("error").path("message").asText("");
        if (!nested.isBlank()) {
            return nested;
        }
        String error = data.path("error").asText("");
        if (!error.isBlank()) {
            return error;
        }
        String message = data.path("message").asText("");
        return message.isBlank() ? "Model request failed: " + status : message;
    }

    private boolean shouldRetryStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    private long retryDelayMs(AnthropicTransport.Response response, int attempt) {
        Long retryAfter = parseRetryAfterMs(response);
        if (retryAfter != null) {
            return retryAfter;
        }
        long base = Math.min(BASE_RETRY_DELAY_MS * (1L << Math.max(0, Math.min(attempt - 1, 10))),
                MAX_RETRY_DELAY_MS);
        long jitter = Math.floorMod(Objects.hash(runtimeConfig.model(), attempt), Math.max(1L, base / 4L + 1L));
        return Math.min(MAX_RETRY_DELAY_MS, base + jitter);
    }

    private Long parseRetryAfterMs(AnthropicTransport.Response response) {
        if (response == null) {
            return null;
        }
        List<String> values = response.headers().get("retry-after");
        if (values == null) {
            values = response.headers().get("Retry-After");
        }
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        try {
            double seconds = Double.parseDouble(value);
            if (seconds >= 0.0d) {
                return Math.round(seconds * 1000.0d);
            }
        } catch (NumberFormatException ignored) {
            // Fall through to HTTP date parsing.
        }
        try {
            long epochMillis = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
            return Math.max(0L, epochMillis - System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record ParsedText(String content, AssistantKind kind) {
    }
}
