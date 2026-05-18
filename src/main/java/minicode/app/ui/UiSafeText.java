package minicode.app.ui;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UiSafeText {
    private static final String REDACTED = "<redacted>";
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(ANTHROPIC_AUTH_TOKEN\\s*=\\s*)(\"|')?[^\\s;,\"']+(\"|')?"),
            Pattern.compile("(?i)(ANTHROPIC_API_KEY\\s*=\\s*)(\"|')?[^\\s;,\"']+(\"|')?"),
            Pattern.compile("(?i)(Authorization\\s*:\\s*Bearer\\s+)[^\\s;,\"']+"),
            Pattern.compile("(?i)(--api-key(?:\\s+|=))[^\\s;,\"']+"),
            Pattern.compile("(?i)(api[_-]?key\\s*[:=]\\s*)(\"|')?[^\\s;,\"']+(\"|')?"),
            Pattern.compile("(?i)(authToken\\s*[:=]\\s*)(\"|')?[^\\s;,\"']+(\"|')?"),
            Pattern.compile("(?i)(providerRawPayload\\s*[:=]\\s*)(\"|')?[^\\s;,\"']+(\"|')?")
    );

    private UiSafeText() {
    }

    static String oneLine(String value) {
        return Objects.requireNonNull(value, "value").replaceAll("\\s+", " ").trim();
    }

    static String redact(String value) {
        String result = Objects.requireNonNull(value, "value");
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + REDACTED));
            }
            matcher.appendTail(buffer);
            result = buffer.toString();
        }
        return result;
    }

    static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    static Preview preview(String value, int maxChars) {
        String redacted = redact(Objects.requireNonNull(value, "value"));
        if (redacted.length() <= maxChars) {
            return new Preview(redacted, false, 0);
        }
        String visible = redacted.substring(0, maxChars);
        String hidden = redacted.substring(maxChars);
        return new Preview(visible, true, Math.max(1, hidden.split("\\R", -1).length));
    }

    record Preview(String text, boolean truncated, int hiddenLines) {
    }
}
