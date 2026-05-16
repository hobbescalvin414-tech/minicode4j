package minicode.mcp;

import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class McpRuntimeTest {
    @Test
    void hydratesWorkingServersAndSummarizesStartFailuresWithoutBlockingOthers() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        configs.put("Good Server", fakeConfig("happy"));
        configs.put("Missing Server", new McpServerConfig(
                "definitely-not-a-command-for-minicode-mcp-test",
                List.of(),
                null,
                Map.of(),
                true,
                Duration.ofMillis(500),
                Duration.ofMillis(500)
        ));

        McpRuntime runtime = McpToolHydrator.hydrate(configs);
        try {
            assertEquals(2, runtime.summaries().size());
            assertEquals(McpServerStatus.CONNECTED, runtime.summaries().get(0).status());
            assertEquals(2, runtime.tools().size());
            assertEquals(McpServerStatus.ERROR, runtime.summaries().get(1).status());
            assertEquals(McpErrorKind.START_FAILED, runtime.summaries().get(1).errorKind().orElseThrow());
        } finally {
            runtime.close();
        }
    }

    @Test
    void hydratedToolsCanBeRegisteredAndExecutedThroughToolRegistry() {
        McpRuntime runtime = McpToolHydrator.hydrate(Map.of("Good Server", fakeConfig("happy")));
        try {
            ToolRegistry registry = new ToolRegistry();
            runtime.tools().forEach(registry::register);

            ToolResult result = registry.execute(
                    new ToolCall("tool-use-1", "mcp__good_server__echo_tool", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("value", "ok")),
                    new ToolContext(Path.of("."), "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"))
            );

            assertFalse(result.error());
            assertTrue(result.content().contains("echo: ok"));
        } finally {
            runtime.close();
        }
    }

    private static McpServerConfig fakeConfig(String mode) {
        return new McpServerConfig(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpStdioServer.class.getName(), mode),
                null,
                Map.of(),
                true,
                Duration.ofMillis(800),
                Duration.ofMillis(800)
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
