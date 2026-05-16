package minicode.tui;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolInputSummarizerTest {
    @Test
    void summarizesReadFileTargetAndWindow() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("path", "src/main/java/minicode/app/MiniCodeApp.java")
                .put("offset", 40)
                .put("limit", 200);

        String summary = ToolInputSummarizer.summarize("read_file", input);

        assertEquals("path=src/main/java/minicode/app/MiniCodeApp.java offset=40 limit=200", summary);
    }

    @Test
    void readFileSummaryIncludesLineModeFields() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("path", "SnakeGame.java")
                .put("lineStart", 280)
                .put("lineCount", 120);

        String summary = ToolInputSummarizer.summarize("read_file", input);

        assertTrue(summary.contains("path=SnakeGame.java"), summary);
        assertTrue(summary.contains("lineStart=280"), summary);
        assertTrue(summary.contains("lineCount=120"), summary);
        assertFalse(summary.contains("offset="), summary);
        assertFalse(summary.contains("limit="), summary);
    }

    @Test
    void summarizesRunCommandWithTruncatedOneLineCommand() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("command", "powershell")
                .set("args", JsonNodeFactory.instance.arrayNode()
                        .add("-NoProfile")
                        .add("-Command")
                        .add("Write-Output '" + "x".repeat(200) + "'"));

        String summary = ToolInputSummarizer.summarize("run_command", input);

        assertTrue(summary.startsWith("cmd=\"powershell -NoProfile -Command Write-Output '"), summary);
        assertTrue(summary.endsWith("...\""), summary);
        assertTrue(summary.length() <= 240, summary);
    }

    @Test
    void writeFileSummaryDoesNotLeakContent() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("path", "secret.txt")
                .put("content", "do not print this content");

        String summary = ToolInputSummarizer.summarize("write_file", input);

        assertEquals("path=secret.txt content_chars=25", summary);
        assertFalse(summary.contains("do not print"));
    }

    @Test
    void grepFilesQuotesRegexQuerySoOperatorsDoNotLookLikeUiSeparators() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("query", "controlY = titleY \\+|startHint.*HEIGHT");

        String summary = ToolInputSummarizer.summarize("grep_files", input);

        assertEquals("query=\"controlY = titleY \\+|startHint.*HEIGHT\"", summary);
    }

    @Test
    void unknownToolFallsBackToCompactTruncatedJson() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("alpha", "a".repeat(300));

        String summary = ToolInputSummarizer.summarize("unknown_tool", input);

        assertTrue(summary.startsWith("{\"alpha\":\""), summary);
        assertTrue(summary.endsWith("..."), summary);
        assertTrue(summary.length() <= 240, summary);
    }

    @Test
    void runCommandSummaryRedactsTokensBeforePrinting() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("command", "powershell")
                .set("args", JsonNodeFactory.instance.arrayNode()
                        .add("-NoProfile")
                        .add("-Command")
                        .add("$env:ANTHROPIC_AUTH_TOKEN='sk-actual-token'; "
                                + "curl -H \"Authorization: Bearer bearer-secret\" "
                                + "--api-key cli-secret"));

        String summary = ToolInputSummarizer.summarize("run_command", input);

        assertTrue(summary.contains("<redacted>"), summary);
        assertFalse(summary.contains("sk-actual-token"), summary);
        assertFalse(summary.contains("bearer-secret"), summary);
        assertFalse(summary.contains("cli-secret"), summary);
    }

    @Test
    void unknownToolJsonSummaryRedactsSensitiveFields() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("api_key", "json-secret")
                .put("authToken", "auth-secret")
                .put("ANTHROPIC_API_KEY", "env-secret");

        String summary = ToolInputSummarizer.summarize("unknown_tool", input);

        assertTrue(summary.contains("<redacted>"), summary);
        assertFalse(summary.contains("json-secret"), summary);
        assertFalse(summary.contains("auth-secret"), summary);
        assertFalse(summary.contains("env-secret"), summary);
    }

    @Test
    void mcpToolSummaryShowsServerToolAndRedactedTruncatedArguments() {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("ANTHROPIC_AUTH_TOKEN", "mcp-secret")
                .put("query", "find " + "x".repeat(260));

        String summary = ToolInputSummarizer.summarize("mcp__search_server__search", input);

        assertTrue(summary.startsWith("server=search_server tool=search args="), summary);
        assertTrue(summary.contains("<redacted>"), summary);
        assertFalse(summary.contains("mcp-secret"), summary);
        assertTrue(summary.length() <= 240, summary);
    }
}
