package minicode.app.ui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class UiToolInputSummarizer {
    private static final int MAX_FIELD_CHARS = 120;
    private static final int MAX_TOTAL_CHARS = 240;

    private UiToolInputSummarizer() {
    }

    static String summarize(String toolName, JsonNode input) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(input, "input");
        String summary = switch (toolName) {
            case "read_file" -> join(
                    field("path", text(input, "path")),
                    field("lineStart", value(input, "lineStart")),
                    field("lineCount", value(input, "lineCount")),
                    field("offset", value(input, "offset")),
                    field("limit", value(input, "limit"))
            );
            case "list_files" -> join(
                    field("path", text(input, "path")),
                    field("maxDepth", value(input, "maxDepth")),
                    field("limit", value(input, "limit"))
            );
            case "grep_files" -> join(
                    field("path", text(input, "path")),
                    quoteField("query", firstText(input, "query", "pattern")),
                    field("maxMatches", firstValue(input, "maxMatches", "limit"))
            );
            case "write_file" -> join(
                    field("path", text(input, "path")),
                    field("content_chars", contentLength(input, "content"))
            );
            case "edit_file", "modify_file" -> field("path", text(input, "path"));
            case "patch_file" -> join(field("path", text(input, "path")),
                    field("replacements", arraySize(input, "replacements")));
            case "run_command" -> quoteField("cmd", commandLine(input));
            case "ask_user" -> quoteField("question", text(input, "question"));
            case "load_skill" -> join(field("name", text(input, "name")), field("skill", text(input, "skill")));
            default -> toolName.startsWith("mcp__") ? summarizeMcp(toolName, input) : unknownSummary(input);
        };
        return UiSafeText.truncate(UiSafeText.redact(summary), MAX_TOTAL_CHARS);
    }

    private static String summarizeMcp(String toolName, JsonNode input) {
        String[] parts = toolName.split("__", 3);
        if (parts.length != 3) {
            return unknownSummary(input);
        }
        return join(field("server", parts[1]), field("tool", parts[2]), field("args", "hidden fields=" + fieldCount(input)));
    }

    private static String unknownSummary(JsonNode input) {
        return "input hidden fields=" + fieldCount(input);
    }

    private static int fieldCount(JsonNode input) {
        return input.isObject() ? input.size() : 0;
    }

    private static String firstText(JsonNode input, String first, String second) {
        String value = text(input, first);
        return value.isEmpty() ? text(input, second) : value;
    }

    private static String firstValue(JsonNode input, String first, String second) {
        String value = value(input, first);
        return value.isEmpty() ? value(input, second) : value;
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String value(JsonNode input, String field) {
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String contentLength(JsonNode input, String field) {
        JsonNode node = input.get(field);
        return node != null && node.isTextual() ? Integer.toString(node.asText().length()) : "";
    }

    private static String arraySize(JsonNode input, String field) {
        JsonNode node = input.get(field);
        return node != null && node.isArray() ? Integer.toString(node.size()) : "";
    }

    private static String commandLine(JsonNode input) {
        String command = text(input, "command");
        if (command.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(command);
        JsonNode args = input.get("args");
        if (args != null && args.isArray()) {
            args.forEach(arg -> parts.add(arg.isTextual() ? arg.asText() : arg.toString()));
        }
        return String.join(" ", parts);
    }

    private static String field(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return name + "=" + UiSafeText.truncate(UiSafeText.oneLine(value), MAX_FIELD_CHARS);
    }

    private static String quoteField(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return name + "=\"" + UiSafeText.truncate(UiSafeText.oneLine(value), MAX_FIELD_CHARS) + "\"";
    }

    private static String join(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part);
            }
        }
        return String.join(" ", present);
    }
}
