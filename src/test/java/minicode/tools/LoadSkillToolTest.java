package minicode.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.skills.LoadedSkill;
import minicode.skills.SkillRegistry;
import minicode.skills.SkillSource;
import minicode.tools.api.ToolContext;
import minicode.tools.builtin.LoadSkillTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSkillToolTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsDiscoveredSkillContent() {
        LoadSkillTool tool = new LoadSkillTool(new SkillRegistry(List.of(new LoadedSkill(
                "review",
                "Review code carefully.",
                tempDir.resolve("review/SKILL.md"),
                SkillSource.PROJECT_JAVA,
                "# Review\n\nReview code carefully."
        ))));

        ToolResult result = tool.run(JsonNodeFactory.instance.objectNode().put("name", "review"), context());

        assertFalse(result.error());
        assertTrue(result.content().contains("SKILL: review"));
        assertTrue(result.content().contains("SOURCE: project_java"));
        assertTrue(result.content().contains("# Review"));
    }

    @Test
    void missingSkillReturnsCorrectableError() {
        LoadSkillTool tool = new LoadSkillTool(new SkillRegistry(List.of()));

        ToolResult result = tool.run(JsonNodeFactory.instance.objectNode().put("name", "missing"), context());

        assertTrue(result.error());
        assertTrue(result.content().contains("Unknown skill: missing"));
        assertTrue(result.content().contains("No skills are currently discovered."));
    }

    @Test
    void rejectsPathLikeNamesDuringValidation() {
        LoadSkillTool tool = new LoadSkillTool(new SkillRegistry(List.of()));

        assertFalse(tool.validateInput(JsonNodeFactory.instance.objectNode().put("name", "../secret")).valid());
        assertFalse(tool.validateInput(JsonNodeFactory.instance.objectNode().put("name", "a/b")).valid());
        assertFalse(tool.validateInput(JsonNodeFactory.instance.objectNode().put("name", "a\\b")).valid());
        assertFalse(tool.validateInput(JsonNodeFactory.instance.objectNode().put("name", "C:\\secret")).valid());
    }

    @Test
    void registryExecutesLoadSkillThroughToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new LoadSkillTool(new SkillRegistry(List.of(new LoadedSkill(
                "review",
                "Review code carefully.",
                tempDir.resolve("review/SKILL.md"),
                SkillSource.PROJECT_JAVA,
                "# Review\n\nReview code carefully."
        )))));

        ToolResult result = registry.execute(
                new minicode.tools.api.ToolCall(
                        "tool-use-1",
                        "load_skill",
                        JsonNodeFactory.instance.objectNode().put("name", "review")
                ),
                context()
        );

        assertFalse(result.error());
        assertTrue(result.content().contains("# Review"));
    }

    private ToolContext context() {
        return new ToolContext(tempDir, "session-1", Optional.empty(), Optional.empty());
    }
}
