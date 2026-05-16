package minicode.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void discoversSkillsFromJavaNativeAndCompatibilityRootsInPrecedenceOrder() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        writeSkill(cwd.resolve(".minicode/skills"), "shared", "project java description");
        writeSkill(appHome.resolve("skills"), "user-only", "user java description");
        writeSkill(cwd.resolve(".mini-code/skills"), "ts-only", "project ts description");
        writeSkill(osHome.resolve(".mini-code/skills"), "user-ts-only", "user ts description");
        writeSkill(cwd.resolve(".claude/skills"), "claude-project", "claude project description");
        writeSkill(osHome.resolve(".claude/skills"), "claude-user", "claude user description");
        writeSkill(appHome.resolve("skills"), "shared", "lower priority user java description");

        List<LoadedSkill> skills = new SkillDiscovery(appHome, cwd, osHome).discover();

        assertEquals(List.of(
                "shared",
                "user-only",
                "ts-only",
                "user-ts-only",
                "claude-project",
                "claude-user"
        ), skills.stream().map(LoadedSkill::name).toList());
        LoadedSkill shared = skills.getFirst();
        assertEquals(SkillSource.PROJECT_JAVA, shared.source());
        assertEquals("project java description", shared.description());
        assertTrue(shared.content().contains("project java description"));
    }

    @Test
    void missingRootsAndMalformedSkillDirectoriesAreIgnored() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        Files.createDirectories(cwd.resolve(".minicode/skills/not-a-skill"));
        Files.writeString(cwd.resolve(".minicode/skills/plain-file.txt"), "ignored");
        writeSkill(cwd.resolve(".minicode/skills"), "good", "Good description.");

        List<LoadedSkill> skills = new SkillDiscovery(appHome, cwd, osHome).discover();

        assertEquals(List.of("good"), skills.stream().map(LoadedSkill::name).toList());
    }

    @Test
    void symlinkSkillDirectoriesAreIgnoredWhenSupported() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        Path root = cwd.resolve(".minicode/skills");
        Path outside = tempDir.resolve("outside-skill");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("SKILL.md"), "# Leak\n\nShould not be discovered.");
        Path link = root.resolve("leak");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            System.out.println("Skipping symlink assertion: symlink creation is unavailable on this platform: "
                    + exception.getMessage());
            return;
        }

        List<LoadedSkill> skills = new SkillDiscovery(appHome, cwd, osHome).discover();

        assertEquals(List.of(), skills.stream().map(LoadedSkill::name).toList());
    }

    @Test
    void extractsDescriptionFromFirstNonHeadingParagraph() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        Path skillDir = cwd.resolve(".minicode/skills/desc");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                # Heading

                Use `desc` workflow accurately.

                More details.
                """);

        LoadedSkill skill = new SkillDiscovery(appHome, cwd, osHome).discover().getFirst();

        assertEquals("Use desc workflow accurately.", skill.description());
    }

    @Test
    void skipsLeadingFrontMatterWhenExtractingDescription() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        Path skillDir = cwd.resolve(".minicode/skills/frontmatter");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: frontmatter
                description: Metadata description should not be parsed.
                ---

                # Front Matter Skill

                Use this workflow from the body.
                """);

        LoadedSkill skill = new SkillDiscovery(appHome, cwd, osHome).discover().getFirst();

        assertEquals("Metadata description should not be parsed.", skill.description());
    }

    @Test
    void usesFrontMatterDescriptionBeforeSubagentStopBody() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        Path skillDir = cwd.resolve(".minicode/skills/using-superpowers");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: using-superpowers
                description: Use when starting any conversation - establishes how to find and use skills
                ---

                <SUBAGENT-STOP>
                If you were dispatched as a subagent to execute a specific task, skip this skill.
                </SUBAGENT-STOP>

                # Using Skills
                """);

        LoadedSkill skill = new SkillDiscovery(appHome, cwd, osHome).discover().getFirst();

        assertEquals("Use when starting any conversation - establishes how to find and use skills", skill.description());
    }

    @Test
    void fallsBackWhenNoDescriptionExists() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        Path appHome = tempDir.resolve("app-home");
        Path osHome = tempDir.resolve("os-home");
        Path skillDir = cwd.resolve(".minicode/skills/emptydesc");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# Only Heading\n");

        LoadedSkill skill = new SkillDiscovery(appHome, cwd, osHome).discover().getFirst();

        assertEquals("No description provided.", skill.description());
    }

    private static void writeSkill(Path root, String name, String description) throws Exception {
        Path skillDir = root.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# " + name + "\n\n" + description + "\n");
    }
}
