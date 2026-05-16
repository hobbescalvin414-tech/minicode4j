package minicode.config;

import minicode.mcp.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitMockProviderDoesNotRequireRealProviderAuth() {
        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                Map.of("MINICODE_PROVIDER", "mock", "MINICODE_MODEL", "mock-model")
        ));

        assertEquals(ProviderKind.MOCK, config.provider());
        assertEquals("mock-model", config.model());
        assertTrue(config.apiKey().isEmpty());
        assertTrue(config.authToken().isEmpty());
    }

    @Test
    void realProviderRequiresModelAndAuthInsteadOfFallingBackToMock() {
        RuntimeConfigException exception = assertThrows(RuntimeConfigException.class, () ->
                RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                        tempDir.resolve("home"),
                        tempDir.resolve("workspace"),
                        Map.of("MINICODE_PROVIDER", "anthropic")
                )));

        assertTrue(exception.getMessage().contains("No model configured"));
    }

    @Test
    void settingsAndEnvAreMergedWithEnvTakingPrecedence() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".minicode"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "anthropic",
                  "model": "claude-from-settings",
                  "baseUrl": "https://settings.example",
                  "apiKey": "settings-key",
                  "maxOutputTokens": 1024,
                  "contextWindow": 200000
                }
                """);
        Files.writeString(cwd.resolve(".minicode").resolve("settings.json"), """
                {
                  "model": "claude-from-project",
                  "baseUrl": "https://project.example",
                  "apiKey": "project-key",
                  "contextWindow": 100000
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                home,
                cwd,
                Map.of(
                        "MINICODE_MODEL", "claude-from-env",
                        "ANTHROPIC_AUTH_TOKEN", "env-token",
                        "MINICODE_MAX_OUTPUT_TOKENS", "2048"
                )
        ));

        assertEquals(ProviderKind.ANTHROPIC, config.provider());
        assertEquals("claude-from-env", config.model());
        assertEquals("https://project.example", config.baseUrl());
        assertEquals("project-key", config.apiKey().orElseThrow());
        assertEquals("env-token", config.authToken().orElseThrow());
        assertEquals(2048, config.maxOutputTokens().orElseThrow());
        assertEquals(100000, config.contextWindow().orElseThrow());
        assertTrue(config.maxSteps().isEmpty());
        assertEquals(java.time.Duration.ofSeconds(300), config.providerTimeout());
        assertTrue(config.sourceSummary().contains(home.resolve("settings.json").toString()));
        assertTrue(config.sourceSummary().contains(cwd.resolve(".minicode").resolve("settings.json").toString()));
    }

    @Test
    void settingsEnvIsMergedLikeTsRuntimeConfig() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".minicode"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "model": "home-top-model",
                  "apiKey": "home-top-key",
                  "env": {
                    "ANTHROPIC_MODEL": "home-env-model",
                    "ANTHROPIC_API_KEY": "home-env-key",
                    "ANTHROPIC_BASE_URL": "https://home-env.example",
                    "MINICODE_MAX_OUTPUT_TOKENS": 1111
                  }
                }
                """);
        Files.writeString(cwd.resolve(".minicode").resolve("settings.json"), """
                {
                  "model": "cwd-top-model",
                  "apiKey": "cwd-top-key",
                  "env": {
                    "ANTHROPIC_MODEL": "cwd-env-model",
                    "ANTHROPIC_API_KEY": "cwd-env-key",
                    "MINICODE_CONTEXT_WINDOW": 222222
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                home,
                cwd,
                Map.of("MINICODE_MAX_OUTPUT_TOKENS", "3333")
        ));

        assertEquals("cwd-env-model", config.model());
        assertEquals("cwd-env-key", config.apiKey().orElseThrow());
        assertEquals("https://home-env.example", config.baseUrl());
        assertEquals(3333, config.maxOutputTokens().orElseThrow());
        assertEquals(222222, config.contextWindow().orElseThrow());
    }

    @Test
    void providerTimeoutDefaultsToFiveMinutesAndCanBeOverridden() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".minicode"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "anthropic",
                  "model": "home-model",
                  "authToken": "home-token",
                  "providerTimeoutSeconds": 180
                }
                """);
        Files.writeString(cwd.resolve(".minicode").resolve("settings.json"), """
                {
                  "providerTimeoutSeconds": 240
                }
                """);

        RuntimeConfig defaultConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                tempDir.resolve("default-home"),
                tempDir.resolve("default-workspace"),
                Map.of(
                        "MINICODE_PROVIDER", "anthropic",
                        "ANTHROPIC_MODEL", "env-model",
                        "ANTHROPIC_AUTH_TOKEN", "env-token"
                )
        ));
        RuntimeConfig settingsConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));
        RuntimeConfig envConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd,
                Map.of("MINICODE_PROVIDER_TIMEOUT_SECONDS", "360")));

        assertEquals(java.time.Duration.ofSeconds(300), defaultConfig.providerTimeout());
        assertEquals(java.time.Duration.ofSeconds(240), settingsConfig.providerTimeout());
        assertEquals(java.time.Duration.ofSeconds(360), envConfig.providerTimeout());
    }

    @Test
    void maxStepsCanBeConfiguredFromSettingsWithProjectOverride() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".minicode"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "maxSteps": 24
                }
                """);
        Files.writeString(cwd.resolve(".minicode").resolve("settings.json"), """
                {
                  "maxSteps": 48
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));

        assertEquals(48, config.maxSteps().orElseThrow());
    }

    @Test
    void settingsLoadMcpServersFromHomeAndProjectWithProjectOverride() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".minicode"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "mcpServers": {
                    "fake": {
                      "command": "home-command",
                      "args": ["--home"],
                      "env": { "HOME_ONLY": "1" },
                      "enabled": true
                    },
                    "disabled": {
                      "command": "disabled-command",
                      "enabled": false
                    }
                  }
                }
                """);
        Files.writeString(cwd.resolve(".minicode").resolve("settings.json"), """
                {
                  "mcpServers": {
                    "fake": {
                      "command": "project-command",
                      "args": ["--project"],
                      "env": { "PROJECT_ONLY": "2" },
                      "cwd": "tools"
                    }
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));

        assertEquals(2, config.mcpServers().size());
        McpServerConfig fake = config.mcpServers().get("fake");
        assertEquals("project-command", fake.command());
        assertEquals(List.of("--project"), fake.args());
        assertEquals(Map.of("HOME_ONLY", "1", "PROJECT_ONLY", "2"), fake.env());
        assertEquals(Optional.of("tools"), fake.cwd());
        assertTrue(fake.enabled());
        assertFalse(config.mcpServers().get("disabled").enabled());
    }
}
