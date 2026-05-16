package minicode.config;

import minicode.mcp.McpServerConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                            Optional<String> authToken, Optional<Integer> maxOutputTokens,
                            Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                            Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers) {
    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, String sourceSummary) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                Duration.ofSeconds(300), sourceSummary, Map.of());
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Duration providerTimeout, String sourceSummary) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                providerTimeout, sourceSummary, Map.of());
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, String sourceSummary,
                         Map<String, McpServerConfig> mcpServers) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                Duration.ofSeconds(300), sourceSummary, mcpServers);
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                         Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = requireText(model, "model");
        this.baseUrl = requireText(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.authToken = Objects.requireNonNull(authToken, "authToken");
        this.maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        this.contextWindow = Objects.requireNonNull(contextWindow, "contextWindow");
        this.maxSteps = requirePositiveOptional(maxSteps, "maxSteps");
        this.providerTimeout = requirePositive(providerTimeout, "providerTimeout");
        this.sourceSummary = requireText(sourceSummary, "sourceSummary");
        this.mcpServers = Map.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration actual = Objects.requireNonNull(value, name);
        if (actual.isZero() || actual.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return actual;
    }

    private static Optional<Integer> requirePositiveOptional(Optional<Integer> value, String name) {
        Optional<Integer> actual = Objects.requireNonNull(value, name);
        actual.ifPresent(number -> {
            if (number <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        });
        return actual;
    }
}
