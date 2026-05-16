package minicode.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class SkillDiscovery {
    private final Path appHome;
    private final Path cwd;
    private final Path osHome;

    public SkillDiscovery(Path appHome, Path cwd) {
        this(appHome, cwd, Path.of(System.getProperty("user.home")));
    }

    public SkillDiscovery(Path appHome, Path cwd, Path osHome) {
        this.appHome = Objects.requireNonNull(appHome, "appHome").toAbsolutePath().normalize();
        this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        this.osHome = Objects.requireNonNull(osHome, "osHome").toAbsolutePath().normalize();
    }

    public List<LoadedSkill> discover() {
        LinkedHashMap<String, LoadedSkill> byName = new LinkedHashMap<>();
        for (SkillRoot root : roots()) {
            for (LoadedSkill skill : listSkillDirs(root)) {
                byName.putIfAbsent(skill.name(), skill);
            }
        }
        return List.copyOf(byName.values());
    }

    private List<SkillRoot> roots() {
        return List.of(
                new SkillRoot(cwd.resolve(".minicode").resolve("skills"), SkillSource.PROJECT_JAVA),
                new SkillRoot(appHome.resolve("skills"), SkillSource.USER_JAVA),
                new SkillRoot(cwd.resolve(".mini-code").resolve("skills"), SkillSource.PROJECT_TS),
                new SkillRoot(osHome.resolve(".mini-code").resolve("skills"), SkillSource.USER_TS),
                new SkillRoot(cwd.resolve(".claude").resolve("skills"), SkillSource.COMPAT_PROJECT),
                new SkillRoot(osHome.resolve(".claude").resolve("skills"), SkillSource.COMPAT_USER)
        );
    }

    private List<LoadedSkill> listSkillDirs(SkillRoot root) {
        Path normalizedRoot = root.path().toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            return List.of();
        }
        List<LoadedSkill> results = new ArrayList<>();
        try (Stream<Path> paths = Files.list(normalizedRoot)) {
            paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> readSkill(normalizedRoot, path, root.source(), results));
        } catch (IOException exception) {
            return List.of();
        }
        return results;
    }

    private void readSkill(Path root, Path skillDir, SkillSource source, List<LoadedSkill> results) {
        Path normalizedSkillDir = skillDir.toAbsolutePath().normalize();
        if (!normalizedSkillDir.startsWith(root)) {
            return;
        }
        Path skillPath = normalizedSkillDir.resolve("SKILL.md").toAbsolutePath().normalize();
        if (!skillPath.startsWith(root)) {
            return;
        }
        String name = normalizedSkillDir.getFileName().toString();
        if (name.isBlank()) {
            return;
        }
        try {
            String content = Files.readString(skillPath, StandardCharsets.UTF_8);
            results.add(new LoadedSkill(name, extractDescription(content), skillPath, source, content));
        } catch (IOException | RuntimeException exception) {
            // Discovery is best-effort: malformed or unreadable skills must not block app startup.
        }
    }

    static String extractDescription(String markdown) {
        String normalized = Objects.requireNonNull(markdown, "markdown").replace("\r\n", "\n");
        FrontMatterSplit split = splitLeadingFrontMatter(normalized);
        String frontMatterDescription = extractFrontMatterDescription(split.frontMatter());
        if (!frontMatterDescription.isBlank()) {
            return frontMatterDescription;
        }
        normalized = split.body();
        for (String rawBlock : normalized.split("\n\n")) {
            String block = rawBlock.trim();
            if (block.isEmpty() || block.startsWith("#")) {
                continue;
            }
            for (String rawLine : block.split("\n")) {
                String line = rawLine.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    return line.replace("`", "");
                }
            }
        }
        return "No description provided.";
    }

    private static FrontMatterSplit splitLeadingFrontMatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            return new FrontMatterSplit("", markdown);
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) {
            return new FrontMatterSplit("", markdown);
        }
        return new FrontMatterSplit(markdown.substring(4, end), markdown.substring(end + "\n---\n".length()));
    }

    private static String extractFrontMatterDescription(String frontMatter) {
        if (frontMatter.isBlank()) {
            return "";
        }
        for (String rawLine : frontMatter.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("description:")) {
                continue;
            }
            String value = line.substring("description:".length()).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            return value.replace("`", "");
        }
        return "";
    }

    private record FrontMatterSplit(String frontMatter, String body) {
    }

    private record SkillRoot(Path path, SkillSource source) {
        private SkillRoot {
            path = Objects.requireNonNull(path, "path");
            source = Objects.requireNonNull(source, "source");
        }
    }
}
