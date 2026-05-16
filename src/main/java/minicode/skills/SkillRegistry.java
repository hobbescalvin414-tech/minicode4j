package minicode.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SkillRegistry {
    private final Map<String, LoadedSkill> skillsByName;

    public SkillRegistry(List<LoadedSkill> skills) {
        Objects.requireNonNull(skills, "skills");
        LinkedHashMap<String, LoadedSkill> byName = new LinkedHashMap<>();
        for (LoadedSkill skill : skills) {
            LoadedSkill actualSkill = Objects.requireNonNull(skill, "skill");
            byName.putIfAbsent(actualSkill.name(), actualSkill);
        }
        this.skillsByName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    public List<SkillSummary> summaries() {
        return skillsByName.values().stream()
                .map(LoadedSkill::summary)
                .toList();
    }

    public Optional<LoadedSkill> load(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skillsByName.get(name.trim()));
    }
}
