package minicode.prompt;

import minicode.mcp.McpServerStatus;
import minicode.mcp.McpServerSummary;
import minicode.skills.SkillSummary;
import minicode.tools.api.Tool;
import minicode.tools.registry.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class SystemPromptBuilder {
    public record Input(Path home, Path cwd, ToolRegistry tools, List<SkillSummary> skills,
                        List<McpServerSummary> mcpServers) {
        public Input(Path home, Path cwd, ToolRegistry tools) {
            this(home, cwd, tools, List.of());
        }

        public Input(Path home, Path cwd, ToolRegistry tools, List<SkillSummary> skills) {
            this(home, cwd, tools, skills, List.of());
        }

        public Input {
            home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            tools = Objects.requireNonNull(tools, "tools");
            skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
            mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
        }
    }

    public String build(Input input) {
        Objects.requireNonNull(input, "input");
        StringJoiner prompt = new StringJoiner("\n\n");
        prompt.add("""
                You are mini-code, a terminal coding assistant.
                Default behavior: inspect the repository, use tools, make code changes when appropriate, and explain results clearly.
                Prefer reading files, searching code, editing files, and running verification commands over giving purely theoretical advice.
                """.strip());
        prompt.add("Current cwd: " + input.cwd());
        prompt.add("""
                You can inspect or modify paths outside the current cwd when the user asks, but tool permissions may pause for approval first.
                When making code changes, keep them minimal, practical, and working-oriented.
                If the user clearly asked you to build, modify, optimize, or generate something, do the work instead of stopping at a plan.
                When several independent files, searches, or inspections are needed, request them in the same tool-call step instead of one at a time. Keep dependent actions sequential when later calls need earlier results.
                If you need user clarification, call the ask_user tool with one concise question and wait for the user reply. Do not ask clarifying questions as plain assistant text.
                Do not choose subjective preferences such as colors, visual style, copy tone, or naming unless the user explicitly told you to decide yourself.
                When using read_file, pay attention to the header fields. If it says TRUNCATED: yes, continue reading with a larger offset before concluding that the file itself is cut off.
                If the user names a skill or clearly asks for a workflow that matches a listed skill, call load_skill before following it.
                """.strip());
        prompt.add("""
                Project and global instruction entry points:
                - Read and follow project AGENTS.md when present.
                - Read and follow global AGENTS.md when present.
                - Java MiniCode currently uses AGENTS.md for project/global instructions; this is equivalent to the TS reference prompt's CLAUDE.md instruction entry point.
                - Local project instructions override broad global preferences when they conflict.
                """.strip());
        prompt.add(toolSection(input.tools()));
        prompt.add(skillSection(input.skills()));
        mcpSection(input.mcpServers()).ifPresent(prompt::add);
        prompt.add("""
                Permission and edit review boundaries:
                - Sensitive path access, command execution, and file edits may pause for Permission review.
                - Do not claim a denied action succeeded. Adapt to denial feedback and continue when possible.
                - If permission is denied with user feedback or a question, address that feedback before requesting more tools.
                - File writing tools use edit review semantics; preserve exact requested content and avoid unrelated rewrites.
                """.strip());
        prompt.add("""
                Structured response protocol:
                - Use <progress> for brief, concrete status updates during multi-step work, especially before or between tool batches, searches, edits, long commands, and verification.
                - Use <progress> only when you are still working and will continue with more tool calls or reasoning.
                - Keep <progress> concise; report what you are doing or what you found.
                - Use <final> only when the task is actually complete and control should return to the user.
                - After <progress>, continue immediately in the next step. Do not stop at a progress note.
                - Plain assistant text may be treated as a completed assistant message.
                """.strip());
        prompt.add("""
                ask_user rules:
                - Call ask_user only when a concrete user decision is required to continue.
                - Ask one concise question and wait for the tool result.
                - Do not ask required clarification as plain assistant text.
                """.strip());
        prompt.add("""
                read_file rules:
                - Use lineStart and lineCount for 1-based line ranges, especially when following line numbers from grep_files.
                - Use offset and limit only for character chunks, character-based continuation after TRUNCATED, or targeted character ranges.
                - Do not combine lineStart/lineCount with offset/limit.
                - For normal source files and documents, prefer the default chunk or a sufficiently large lineCount instead of tiny character limits.
                - Read the header. If TRUNCATED: yes appears, continue with the indicated next offset or lineStart before concluding content is missing.
                """.strip());
        prompt.add("""
                run_command rules:
                - Prefer explicit argv-style arguments when the tool supports them.
                - single-string commands are supported for compatibility.
                - Shell snippets are limited and permission-controlled; use them only when a real shell expression is needed.
                - On Windows, prefer simple `cmd /c ...` wrappers for simple shell commands.
                - For complex PowerShell, use `powershell -NoProfile -Command ...` and quote the whole command carefully; avoid mixing unescaped PowerShell env var assignments such as `$env:PATH=...` in a nested one-line shell command.
                - When setting JDK/Maven for verification, prefer full executable paths over one-line PowerShell environment mutation.
                - Command results and denials must be fed back into the next model step.
                """.strip());
        prompt.add("""
                Exact-text edit and patch rules:
                - For exact-text replacement, the old text must match the file exactly.
                - If exact text is not found, reread the relevant file range and retry with the exact current text.
                - Keep patch_file/edit_file/modify_file changes scoped to the requested task.
                """.strip());
        prompt.add("""
                Large output rules:
                - Tool results may be replaced with <persisted-output ...> references when large.
                - Treat replacement text as a pointer to stored output, not as the full original output.
                - Continue using available summaries and reread or rerun narrower commands when needed.
                """.strip());
        maybeRead(input.home().resolve("AGENTS.md"), "Global instructions", prompt);
        maybeRead(input.cwd().resolve("AGENTS.md"), "Project instructions", prompt);
        return prompt.toString();
    }

    private String toolSection(ToolRegistry registry) {
        StringBuilder builder = new StringBuilder("Available tools:");
        if (registry.list().isEmpty()) {
            builder.append("\n- none");
            return builder.toString();
        }
        for (Tool tool : registry.list()) {
            builder.append("\n- ")
                    .append(tool.metadata().name())
                    .append(": ")
                    .append(tool.metadata().description())
                    .append("\n  schema: ")
                    .append(tool.inputSchema().toString());
        }
        return builder.toString();
    }

    private String skillSection(List<SkillSummary> skills) {
        StringBuilder builder = new StringBuilder("Available skills:");
        if (skills.isEmpty()) {
            builder.append("\n- none discovered");
            return builder.toString();
        }
        for (SkillSummary skill : skills) {
            builder.append("\n- ")
                    .append(skill.name())
                    .append(": ")
                    .append(truncate(skill.description(), 300));
        }
        return builder.toString();
    }

    private java.util.Optional<String> mcpSection(List<McpServerSummary> mcpServers) {
        if (mcpServers.isEmpty()) {
            return java.util.Optional.empty();
        }
        StringBuilder builder = new StringBuilder("Configured MCP servers:");
        boolean hasConnected = false;
        for (McpServerSummary server : mcpServers) {
            builder.append("\n- ")
                    .append(server.name())
                    .append(": ")
                    .append(server.status().displayName())
                    .append(", tools=")
                    .append(server.toolCount());
            server.error().ifPresent(error -> builder.append(" (").append(truncate(error, 200)).append(")"));
            hasConnected = hasConnected || server.status() == McpServerStatus.CONNECTED;
        }
        if (hasConnected) {
            builder.append("\nConnected MCP tools are exposed in the tool list with names prefixed like mcp__server__tool.");
        }
        return java.util.Optional.of(builder.toString());
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    private void maybeRead(Path path, String label, StringJoiner prompt) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            prompt.add(label + " from " + path + ":\n" + Files.readString(path));
        } catch (IOException exception) {
            prompt.add(label + " from " + path + " could not be read: " + exception.getMessage());
        }
    }
}
