package minicode.tui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class ToolInputSummarizer {
    private static final int MAX_FIELD_CHARS = 120;
    private static final int MAX_TOTAL_CHARS = 240;
    private static final String REDACTED = "<redacted>";
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(ANTHROPIC_AUTH_TOKEN\\s*=\\s*)('|\")?[^\\s;,&\"']+('|\")?"),
            Pattern.compile("(?i)(ANTHROPIC_API_KEY\\s*=\\s*)('|\")?[^\\s;,&\"']+('|\")?"),
            Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)[^\\s;,&\"']+"),
            Pattern.compile("(?i)(--api-key(?:\\s+|=))[^\\s;,&\"']+"),
            Pattern.compile("(?i)(api_key\\s*=\\s*)('|\")?[^\\s;,&\"']+('|\")?"),
            Pattern.compile("(?i)(authToken\\s*=\\s*)('|\")?[^\\s;,&\"']+('|\")?"),
            Pattern.compile("(?i)(\"(?:ANTHROPIC_AUTH_TOKEN|ANTHROPIC_API_KEY|api_key|authToken)\"\\s*:\\s*\")[^\"]*(\")")
    );

    private ToolInputSummarizer() {
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
            case "edit_file" -> join(field("path", text(input, "path")));
            case "patch_file" -> join(
                    field("path", text(input, "path")),
                    field("replacements", arraySize(input, "replacements"))
            );
            case "modify_file" -> join(field("path", text(input, "path")));
            case "run_command" -> quoteField("cmd", commandLine(input));
            case "ask_user" -> quoteField("question", text(input, "question"));
            case "load_skill" -> join(
                    field("name", text(input, "name")),
                    field("skill", text(input, "skill"))
            );
            default -> toolName.startsWith("mcp__") ? summarizeMcp(toolName, input) : compactJson(input);
        };
        return truncate(redact(summary), MAX_TOTAL_CHARS);
    }

    private static String summarizeMcp(String toolName, JsonNode input) {
        String[] parts = toolName.split("__", 3);
        if (parts.length != 3) {
            return compactJson(input);
        }
        return join(
                field("server", parts[1]),
                field("tool", parts[2]),
                field("args", compactJson(input))
        );
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
        if (node == null || node.isNull() || !node.isTextual()) {
            return "";
        }
        return Integer.toString(node.asText().length());
    }

    private static String arraySize(JsonNode input, String field) {
        JsonNode node = input.get(field);
        if (node == null || !node.isArray()) {
            return "";
        }
        return Integer.toString(node.size());
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
        return name + "=" + truncate(redact(oneLine(value)), MAX_FIELD_CHARS);
    }

    private static String quoteField(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return name + "=\"" + truncate(redact(oneLine(value)), MAX_FIELD_CHARS) + "\"";
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

    private static String compactJson(JsonNode input) {
        return oneLine(input.toString());
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
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

    private static String redact(String value) {
        String redacted = value;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(matchResult -> {
                if (matchResult.groupCount() >= 2
                        && matchResult.group(1).startsWith("\"")
                        && matchResult.group(2) != null) {
                    return matchResult.group(1) + REDACTED + matchResult.group(2);
                }
                return matchResult.group(1) + REDACTED;
            });
        }
        return redacted;
    }
}
