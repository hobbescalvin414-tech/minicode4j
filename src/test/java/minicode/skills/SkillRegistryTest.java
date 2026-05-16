package minicode.skills;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillRegistryTest {
    @Test
    void registryExposesSummariesAndLoadsByName() {
        LoadedSkill skill = new LoadedSkill(
                "java-test",
                "Use Java test workflow.",
                Path.of("C:/skills/java-test/SKILL.md"),
                SkillSource.PROJECT_JAVA,
                "# Java Test\n\nUse Java test workflow."
        );

        SkillRegistry registry = new SkillRegistry(List.of(skill));

        assertEquals(List.of(new SkillSummary(
                "java-test",
                "Use Java test workflow.",
                Path.of("C:/skills/java-test/SKILL.md"),
                SkillSource.PROJECT_JAVA
        )), registry.summaries());
        assertEquals(Optional.of(skill), registry.load("java-test"));
        assertEquals(Optional.empty(), registry.load("missing"));
    }

    @Test
    void registryRejectsBlankLookupName() {
        SkillRegistry registry = new SkillRegistry(List.of());

        assertEquals(Optional.empty(), registry.load(" "));
    }

    @Test
    void summariesPreserveInputOrder() {
        LoadedSkill first = new LoadedSkill(
                "first",
                "First description.",
                Path.of("C:/skills/first/SKILL.md"),
                SkillSource.PROJECT_JAVA,
                "# First\n\nFirst description."
        );
        LoadedSkill second = new LoadedSkill(
                "second",
                "Second description.",
                Path.of("C:/skills/second/SKILL.md"),
                SkillSource.USER_JAVA,
                "# Second\n\nSecond description."
        );
        LoadedSkill third = new LoadedSkill(
                "third",
                "Third description.",
                Path.of("C:/skills/third/SKILL.md"),
                SkillSource.PROJECT_TS,
                "# Third\n\nThird description."
        );

        SkillRegistry registry = new SkillRegistry(List.of(first, second, third));

        assertEquals(List.of("first", "second", "third"),
                registry.summaries().stream().map(SkillSummary::name).toList());
    }
}
