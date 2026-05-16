package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.config.RuntimeConfig;
import minicode.model.ModelMetadata;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AnthropicModelsApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuntimeConfig runtimeConfig;
    private final AnthropicTransport transport;

    public AnthropicModelsApiClient(RuntimeConfig runtimeConfig, AnthropicTransport transport) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public AnthropicTransport transport() {
        return transport;
    }

    public Optional<ModelMetadata> fetch(String modelId) {
        String actualModelId = Objects.requireNonNull(modelId, "modelId");
        if (actualModelId.isBlank()) {
            return Optional.empty();
        }
        AnthropicTransport.Response response;
        try {
            response = transport.get(modelsUrl(actualModelId), headers());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (!response.ok()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            Optional<Long> maxInputTokens = root.has("max_input_tokens")
                    ? Optional.of(root.get("max_input_tokens").asLong())
                    : Optional.empty();
            Optional<Integer> maxOutputTokens = root.has("max_tokens")
                    ? Optional.of(root.get("max_tokens").asInt())
                    : Optional.empty();
            if (maxInputTokens.isEmpty() && maxOutputTokens.isEmpty()) {
                return Optional.empty();
            }
            String id = root.path("id").asText(actualModelId);
            return Optional.of(new ModelMetadata(id, maxInputTokens, maxOutputTokens));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String modelsUrl(String modelId) {
        return runtimeConfig.baseUrl().replaceAll("/+$", "") + "/v1/models/" + modelId;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("anthropic-version", "2023-06-01");
        runtimeConfig.authToken().ifPresent(token -> headers.put("Authorization", "Bearer " + token));
        if (runtimeConfig.authToken().isEmpty()) {
            runtimeConfig.apiKey().ifPresent(key -> headers.put("x-api-key", key));
        }
        return headers;
    }
}
