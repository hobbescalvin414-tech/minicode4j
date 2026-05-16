package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.skills.LoadedSkill;
import minicode.skills.SkillRegistry;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.tools.validation.ToolInputValidation;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class LoadSkillTool implements Tool {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "load_skill",
            "Load the full contents of a named SKILL.md file so you can follow that workflow accurately.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.READ),
            ToolStatus.AVAILABLE
    );

    private final SkillRegistry skillRegistry;

    public LoadSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        return ToolInputValidation.object(input)
                .requiredString("name")
                .custom((json, builder) -> {
                    JsonNode nameNode = json == null ? null : json.get("name");
                    if (nameNode == null || !nameNode.isTextual()) {
                        return;
                    }
                    String name = nameNode.asText().trim();
                    if (name.isEmpty()) {
                        builder.addError("name must not be blank");
                    }
                    if (isPathLike(name)) {
                        builder.addError("name must be a discovered skill name, not a path");
                    }
                    builder.normalized().put("name", name);
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        String name = normalizedInput.get("name").asText().trim();
        return skillRegistry.load(name)
                .map(this::formatSkill)
                .orElseGet(() -> ToolResult.error(unknownSkill(name)));
    }

    private ToolResult formatSkill(LoadedSkill skill) {
        return ToolResult.ok(String.join("\n",
                "SKILL: " + skill.name(),
                "SOURCE: " + skill.source().label(),
                "PATH: " + skill.path(),
                "",
                skill.content()
        ));
    }

    private String unknownSkill(String name) {
        if (skillRegistry.summaries().isEmpty()) {
            return "Unknown skill: " + name + "\nNo skills are currently discovered.";
        }
        String available = skillRegistry.summaries().stream()
                .map(summary -> summary.name())
                .collect(Collectors.joining(", "));
        return "Unknown skill: " + name + "\nAvailable skills: " + available;
    }

    private static boolean isPathLike(String name) {
        return name.contains("..")
                || name.contains("/")
                || name.contains("\\")
                || name.contains(":");
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode name = properties.putObject("name");
        name.put("type", "string");
        name.put("description", "Name of the discovered skill to load.");

        ArrayNode required = schema.putArray("required");
        required.add("name");

        return schema;
    }
}
