package minicode.prompt;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.mcp.McpServerStatus;
import minicode.mcp.McpServerSummary;
import minicode.skills.SkillSource;
import minicode.skills.SkillSummary;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void promptIncludesWorkspaceInstructionsToolsAndCoreProtocols() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        Files.writeString(home.resolve("AGENTS.md"), "global instruction");
        Files.writeString(cwd.resolve("AGENTS.md"), "project instruction");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool());

        String prompt = new SystemPromptBuilder().build(new SystemPromptBuilder.Input(home, cwd, registry));

        assertTrue(prompt.contains("You are mini-code, a terminal coding assistant."));
        assertTrue(prompt.contains("Default behavior: inspect the repository, use tools, make code changes when appropriate, and explain results clearly."));
        assertTrue(prompt.contains("Prefer reading files, searching code, editing files, and running verification commands over giving purely theoretical advice."));
        assertTrue(prompt.contains("Current cwd: " + cwd.toAbsolutePath().normalize()));
        assertTrue(prompt.contains("You can inspect or modify paths outside the current cwd when the user asks, but tool permissions may pause for approval first."));
        assertTrue(prompt.contains("When making code changes, keep them minimal, practical, and working-oriented."));
        assertTrue(prompt.contains("If the user clearly asked you to build, modify, optimize, or generate something, do the work instead of stopping at a plan."));
        assertTrue(prompt.contains("If you need user clarification, call the ask_user tool with one concise question and wait for the user reply. Do not ask clarifying questions as plain assistant text."));
        assertTrue(prompt.contains("Do not choose subjective preferences such as colors, visual style, copy tone, or naming unless the user explicitly told you to decide yourself."));
        assertTrue(prompt.contains("When using read_file, pay attention to the header fields. If it says TRUNCATED: yes, continue reading with a larger offset before concluding that the file itself is cut off."));
        assertTrue(prompt.contains("Global instructions"));
        assertTrue(prompt.contains("global instruction"));
        assertTrue(prompt.contains("Project instructions"));
        assertTrue(prompt.contains("project instruction"));
        assertTrue(prompt.contains("fake_tool"));
        assertTrue(prompt.contains("Fake tool description"));
        assertTrue(prompt.contains("\"type\":\"object\""));
        assertTrue(prompt.contains("Permission"));
        assertTrue(prompt.contains("edit review"));
        assertTrue(prompt.contains("If permission is denied with user feedback or a question, address that feedback before requesting more tools."));
        assertTrue(prompt.contains("<progress>"));
        assertTrue(prompt.contains("Use <progress> for brief, concrete status updates during multi-step work, especially before or between tool batches, searches, edits, long commands, and verification."));
        assertTrue(prompt.contains("Keep <progress> concise; report what you are doing or what you found."));
        assertTrue(prompt.contains("<final>"));
        assertTrue(prompt.contains("ask_user"));
        assertTrue(prompt.contains("read_file"));
        assertTrue(prompt.contains("TRUNCATED"));
        assertTrue(prompt.contains("When several independent files, searches, or inspections are needed, request them in the same tool-call step instead of one at a time. Keep dependent actions sequential when later calls need earlier results."));
        assertTrue(prompt.contains("Use lineStart and lineCount for 1-based line ranges, especially when following line numbers from grep_files."));
        assertTrue(prompt.contains("Use offset and limit only for character chunks, character-based continuation after TRUNCATED, or targeted character ranges."));
        assertTrue(prompt.contains("Do not combine lineStart/lineCount with offset/limit."));
        assertTrue(prompt.contains("prefer the default chunk or a sufficiently large lineCount instead of tiny character limits"));
        assertTrue(prompt.contains("continue with the indicated next offset or lineStart"));
        assertTrue(prompt.contains("run_command"));
        assertTrue(prompt.contains("argv"));
        assertTrue(prompt.contains("single-string"));
        assertTrue(prompt.contains("cmd /c"));
        assertTrue(prompt.contains("powershell -NoProfile -Command"));
        assertTrue(prompt.contains("avoid mixing unescaped PowerShell env var assignments"));
        assertFalse(prompt.contains("E:\\maven\\apache-maven-3.9.11\\bin\\mvn.cmd"));
        assertFalse(prompt.contains("[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new()"));
        assertEquals(1, occurrences(prompt, "powershell -NoProfile -Command"));
        assertTrue(prompt.contains("exact-text"));
        assertTrue(prompt.contains("persisted-output"));
    }

    @Test
    void promptIncludesAvailableSkillsWithoutFullSkillContent() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool());
        List<SkillSummary> skills = List.of(new SkillSummary(
                "review",
                "Review code carefully.",
                cwd.resolve(".minicode/skills/review/SKILL.md"),
                SkillSource.PROJECT_JAVA
        ));

        String prompt = new SystemPromptBuilder().build(new SystemPromptBuilder.Input(home, cwd, registry, skills));

        assertTrue(prompt.contains("If the user names a skill or clearly asks for a workflow that matches a listed skill, call load_skill before following it."));
        assertTrue(prompt.contains("Available skills:"));
        assertTrue(prompt.contains("- review: Review code carefully."));
        assertFalse(prompt.contains("PATH:"));
        assertFalse(prompt.contains("SOURCE:"));
        assertFalse(prompt.contains("# Review"));
    }

    @Test
    void promptStatesWhenNoSkillsDiscovered() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        ToolRegistry registry = new ToolRegistry();

        String prompt = new SystemPromptBuilder().build(new SystemPromptBuilder.Input(home, cwd, registry, List.of()));

        assertTrue(prompt.contains("Available skills:\n- none discovered"));
    }

    @Test
    void promptIncludesConfiguredMcpServerSummaryWithoutResourcesOrPromptsHints() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool());

        String prompt = new SystemPromptBuilder().build(new SystemPromptBuilder.Input(
                home,
                cwd,
                registry,
                List.of(),
                List.of(new McpServerSummary(
                        "fake",
                        "java FakeMcpServer",
                        McpServerStatus.CONNECTED,
                        2,
                        Optional.empty()
                ))
        ));

        assertTrue(prompt.contains("Configured MCP servers:"));
        assertTrue(prompt.contains("- fake: connected, tools=2"));
        assertTrue(prompt.contains("Connected MCP tools are exposed in the tool list with names prefixed like mcp__server__tool."));
        assertFalse(prompt.contains("resources"));
        assertFalse(prompt.contains("prompts"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class FakeTool implements Tool {
        private static final ObjectNode SCHEMA = JsonNodeFactory.instance.objectNode()
                .put("type", "object");

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata("fake_tool", "Fake tool description", SCHEMA,
                    ToolOrigin.BUILTIN, Set.of(ToolCapability.READ), ToolStatus.AVAILABLE);
        }

        @Override
        public ObjectNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ValidationResult validateInput(com.fasterxml.jackson.databind.JsonNode input) {
            return ValidationResult.valid(input);
        }

        @Override
        public ToolResult run(com.fasterxml.jackson.databind.JsonNode normalizedInput, ToolContext toolContext) {
            return ToolResult.ok("ok");
        }
    }
}
