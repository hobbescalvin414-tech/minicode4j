package minicode.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.mcp.McpServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RuntimeConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(300);

    private RuntimeConfigLoader() {
    }

    public record Input(Path home, Path cwd, Map<String, String> env) {
        public Input {
            home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            env = Map.copyOf(Objects.requireNonNull(env, "env"));
        }
    }

    public static RuntimeConfig load(Path home, Path cwd) {
        return load(new Input(home, cwd, System.getenv()));
    }

    public static RuntimeConfig load(Input input) {
        Objects.requireNonNull(input, "input");
        Path homeSettingsPath = input.home().resolve("settings.json");
        Path cwdSettingsPath = input.cwd().resolve(".minicode").resolve("settings.json");
        JsonNode homeSettings = readSettings(homeSettingsPath);
        JsonNode cwdSettings = readSettings(cwdSettingsPath);
        ProviderKind provider = ProviderKind.parse(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_PROVIDER", "provider", "ANTHROPIC"));
        String model = firstNonBlank(
                firstEnvText(input.env(), homeSettings, cwdSettings, "MINICODE_MODEL"),
                firstEnvText(input.env(), homeSettings, cwdSettings, "ANTHROPIC_MODEL"),
                firstTopLevelText(homeSettings, cwdSettings, "model"),
                firstTopLevelText(homeSettings, cwdSettings, "anthropicModel")
        );
        String baseUrl = firstText(input.env(), homeSettings, cwdSettings, "ANTHROPIC_BASE_URL", "baseUrl",
                DEFAULT_ANTHROPIC_BASE_URL);
        Optional<String> apiKey = optionalText(firstText(input.env(), homeSettings, cwdSettings,
                "ANTHROPIC_API_KEY", "apiKey", ""));
        Optional<String> authToken = optionalText(firstText(input.env(), homeSettings, cwdSettings,
                "ANTHROPIC_AUTH_TOKEN", "authToken", ""));
        Optional<Integer> maxOutputTokens = positiveInteger(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_MAX_OUTPUT_TOKENS", "maxOutputTokens", ""));
        Optional<Integer> contextWindow = positiveInteger(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_CONTEXT_WINDOW", "contextWindow", ""));
        Optional<Integer> maxSteps = positiveInteger(firstTopLevelText(homeSettings, cwdSettings, "maxSteps"));
        Duration providerTimeout = providerTimeout(firstText(input.env(), homeSettings, cwdSettings,
                "MINICODE_PROVIDER_TIMEOUT_SECONDS", "providerTimeoutSeconds", ""));
        Map<String, McpServerConfig> mcpServers = mcpServers(homeSettings, cwdSettings);

        if (model.isBlank()) {
            throw new RuntimeConfigException("No model configured. Set MINICODE_MODEL, ANTHROPIC_MODEL, or home settings.json model.");
        }
        if (provider != ProviderKind.MOCK && apiKey.isEmpty() && authToken.isEmpty()) {
            throw new RuntimeConfigException("No auth configured. Set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN in env or home settings.json.");
        }

        return new RuntimeConfig(
                provider,
                model,
                baseUrl,
                apiKey,
                authToken,
                maxOutputTokens,
                contextWindow,
                maxSteps,
                providerTimeout,
                "home=" + input.home() + "; cwd=" + input.cwd()
                        + "; homeSettings=" + homeSettingsPath
                        + "; cwdSettings=" + cwdSettingsPath
                        + "; env"
                ,
                mcpServers
        );
    }

    private static Map<String, McpServerConfig> mcpServers(JsonNode homeSettings, JsonNode cwdSettings) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        mergeMcpServers(merged, homeSettings == null ? null : homeSettings.get("mcpServers"));
        mergeMcpServers(merged, cwdSettings == null ? null : cwdSettings.get("mcpServers"));
        return Map.copyOf(merged);
    }

    private static void mergeMcpServers(Map<String, McpServerConfig> target, JsonNode servers) {
        if (servers == null || !servers.isObject()) {
            return;
        }
        servers.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                return;
            }
            McpServerConfig existing = target.get(entry.getKey());
            target.put(entry.getKey(), mergeMcpServer(existing, node));
        });
    }

    private static McpServerConfig mergeMcpServer(McpServerConfig existing, JsonNode node) {
        String command = settingsText(node, "command");
        if (command.isBlank() && existing != null) {
            command = existing.command();
        }
        List<String> args = node.has("args") && node.get("args").isArray()
                ? stringList(node.get("args"))
                : existing == null ? List.of() : existing.args();
        Map<String, String> env = new LinkedHashMap<>();
        if (existing != null) {
            env.putAll(existing.env());
        }
        JsonNode envNode = node.get("env");
        if (envNode != null && envNode.isObject()) {
            envNode.fields().forEachRemaining(entry -> env.put(entry.getKey(), entry.getValue().asText("")));
        }
        String cwd = settingsText(node, "cwd");
        if (cwd.isBlank() && existing != null) {
            cwd = existing.cwd().orElse(null);
        }
        boolean enabled = node.has("enabled") ? node.get("enabled").asBoolean(true) : existing == null || existing.enabled();
        Duration initializeTimeout = existing == null ? null : existing.initializeTimeout();
        Duration callTimeout = existing == null ? null : existing.callTimeout();
        return new McpServerConfig(command, args, cwd, env, enabled, initializeTimeout, callTimeout);
    }

    private static List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText("")));
        return List.copyOf(result);
    }

    private static JsonNode readSettings(Path path) {
        if (!Files.exists(path)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException exception) {
            throw new RuntimeConfigException("Failed to read settings file: " + path, exception);
        }
    }

    private static String firstText(Map<String, String> env, JsonNode homeSettings, JsonNode cwdSettings,
                                    String envName, String settingsName, String fallback) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String cwdEnvValue = settingsEnvText(cwdSettings, envName);
        if (!cwdEnvValue.isBlank()) {
            return cwdEnvValue;
        }
        String homeEnvValue = settingsEnvText(homeSettings, envName);
        if (!homeEnvValue.isBlank()) {
            return homeEnvValue;
        }

        String cwdTopLevelValue = settingsText(cwdSettings, settingsName);
        if (!cwdTopLevelValue.isBlank()) {
            return cwdTopLevelValue;
        }
        String homeTopLevelValue = settingsText(homeSettings, settingsName);
        if (!homeTopLevelValue.isBlank()) {
            return homeTopLevelValue;
        }
        return fallback;
    }

    private static String firstEnvText(Map<String, String> env, JsonNode homeSettings, JsonNode cwdSettings,
                                       String envName) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        String cwdEnvValue = settingsEnvText(cwdSettings, envName);
        if (!cwdEnvValue.isBlank()) {
            return cwdEnvValue;
        }
        return settingsEnvText(homeSettings, envName);
    }

    private static String firstTopLevelText(JsonNode homeSettings, JsonNode cwdSettings, String settingsName) {
        String cwdTopLevelValue = settingsText(cwdSettings, settingsName);
        if (!cwdTopLevelValue.isBlank()) {
            return cwdTopLevelValue;
        }
        return settingsText(homeSettings, settingsName);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String settingsEnvText(JsonNode settings, String envName) {
        JsonNode env = settings == null ? null : settings.get("env");
        if (env == null || !env.isObject()) {
            return "";
        }
        return text(env.get(envName));
    }

    private static String settingsText(JsonNode settings, String settingsName) {
        return text(settings == null ? null : settings.get(settingsName));
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asText("");
        return text.isBlank() ? "" : text.trim();
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Optional<Integer> positiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Duration providerTimeout(String value) {
        Optional<Integer> seconds = positiveInteger(value);
        return seconds.map(Duration::ofSeconds).orElse(DEFAULT_PROVIDER_TIMEOUT);
    }
}
