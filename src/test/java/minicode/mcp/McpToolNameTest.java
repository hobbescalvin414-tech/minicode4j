package minicode.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpToolNameTest {
    @Test
    void sanitizesServerAndToolSegmentsForRegistryName() {
        assertEquals(
                "mcp__my_server_1__echo_tool",
                McpToolName.wrappedName("My Server 1", "Echo Tool!")
        );
    }

    @Test
    void usesFallbackSegmentWhenSanitizedValueIsEmpty() {
        assertEquals("mcp__server__tool", McpToolName.wrappedName("!!!", "???"));
    }
}
