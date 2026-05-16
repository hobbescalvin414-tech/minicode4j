package minicode.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class ModelLimits {
    private static final MaxOutputTokens UNKNOWN_MAX_OUTPUT_TOKENS = new MaxOutputTokens(32_000, 64_000);
    private static final ContextWindow UNKNOWN_CONTEXT_WINDOW = new ContextWindow(128_000, 8_000);

    private static final List<MaxOutputRule> MAX_OUTPUT_RULES = List.of(
            rule(List.of("claude-opus-4-6", "claude opus 4.6", "opus-4-6"), 128_000, 128_000),
            rule(List.of("claude-sonnet-4-6", "claude sonnet 4.6", "sonnet-4-6"), 64_000, 64_000),
            rule(List.of("claude-haiku-4-5", "claude haiku 4.5", "haiku-4-5"), 64_000, 64_000),
            rule(List.of("claude-opus-4-1", "claude opus 4.1", "opus-4-1", "claude-opus-4",
                    "claude opus 4", "opus-4"), 32_000, 32_000),
            rule(List.of("claude-sonnet-4", "claude sonnet 4", "sonnet-4"), 64_000, 64_000),
            rule(List.of("mimo-v2.5-pro", "mimo v2.5 pro", "mimo-v2.5", "mimo v2.5"), 16_000, 64_000),
            rule(List.of("claude-3-7-sonnet", "claude 3.7 sonnet", "3-7-sonnet"), 8_192, 8_192),
            rule(List.of("claude-3-5-sonnet", "claude 3.5 sonnet", "3-5-sonnet", "claude-3-sonnet"),
                    8_192, 8_192),
            rule(List.of("claude-3-5-haiku", "claude 3.5 haiku", "3-5-haiku"), 8_192, 8_192),
            rule(List.of("claude-3-opus", "claude 3 opus"), 4_096, 4_096),
            rule(List.of("claude-3-haiku", "claude 3 haiku"), 4_096, 4_096)
    );

    private static final List<ContextRule> CONTEXT_RULES = List.of(
            contextRule(List.of("claude-opus-4-6", "claude opus 4.6", "opus-4-6"), 200_000, 16_000),
            contextRule(List.of("claude-sonnet-4-6", "claude sonnet 4.6", "sonnet-4-6"), 200_000, 16_000),
            contextRule(List.of("claude-haiku-4-5", "claude haiku 4.5", "haiku-4-5"), 200_000, 16_000),
            contextRule(List.of("claude-opus-4-1", "claude opus 4.1", "opus-4-1", "claude-opus-4",
                    "claude opus 4", "opus-4"), 200_000, 16_000),
            contextRule(List.of("claude-sonnet-4", "claude sonnet 4", "sonnet-4"), 200_000, 16_000),
            contextRule(List.of("mimo-v2.5-pro", "mimo v2.5 pro", "mimo-v2.5", "mimo v2.5"), 1_048_576, 16_000),
            contextRule(List.of("claude-3-7-sonnet", "claude 3.7 sonnet", "3-7-sonnet"), 200_000, 8_192),
            contextRule(List.of("claude-3-5-sonnet", "claude 3.5 sonnet", "3-5-sonnet", "claude-3-sonnet"),
                    200_000, 8_192),
            contextRule(List.of("claude-3-5-haiku", "claude 3.5 haiku", "3-5-haiku"), 200_000, 8_192),
            contextRule(List.of("claude-3-opus", "claude 3 opus"), 200_000, 4_096),
            contextRule(List.of("claude-3-haiku", "claude 3 haiku"), 200_000, 4_096)
    );

    private ModelLimits() {
    }

    public static int resolveMaxOutputTokens(String model, Optional<Integer> configuredMaxOutputTokens) {
        MaxOutputTokens limits = maxOutputTokens(model);
        if (configuredMaxOutputTokens.isPresent() && configuredMaxOutputTokens.orElseThrow() > 0) {
            return Math.min(configuredMaxOutputTokens.orElseThrow(), limits.upperLimit());
        }
        return limits.defaultValue();
    }

    public static ContextWindow contextWindow(String model) {
        String normalized = normalize(model);
        for (ContextRule rule : CONTEXT_RULES) {
            if (rule.matches(normalized)) {
                return rule.window();
            }
        }
        return UNKNOWN_CONTEXT_WINDOW;
    }

    public static boolean isKnownContextModel(String model) {
        String normalized = normalize(model);
        return CONTEXT_RULES.stream().anyMatch(rule -> rule.matches(normalized));
    }

    private static MaxOutputTokens maxOutputTokens(String model) {
        String normalized = normalize(model);
        for (MaxOutputRule rule : MAX_OUTPUT_RULES) {
            if (rule.matches(normalized)) {
                return rule.limits();
            }
        }
        return UNKNOWN_MAX_OUTPUT_TOKENS;
    }

    private static String normalize(String model) {
        return Objects.requireNonNull(model, "model").trim().toLowerCase(Locale.ROOT);
    }

    private static MaxOutputRule rule(List<String> patterns, int defaultValue, int upperLimit) {
        return new MaxOutputRule(patterns, new MaxOutputTokens(defaultValue, upperLimit));
    }

    private static ContextRule contextRule(List<String> patterns, long contextWindow, long outputReserve) {
        return new ContextRule(patterns, new ContextWindow(contextWindow, outputReserve));
    }

    public record ContextWindow(long contextWindow, long outputReserve) {
        public ContextWindow {
            if (contextWindow <= 0 || outputReserve < 0) {
                throw new IllegalArgumentException("context window must be positive and reserve non-negative");
            }
        }
    }

    private record MaxOutputTokens(int defaultValue, int upperLimit) {
        private MaxOutputTokens {
            if (defaultValue <= 0 || upperLimit <= 0) {
                throw new IllegalArgumentException("max output token limits must be positive");
            }
        }
    }

    private record MaxOutputRule(List<String> patterns, MaxOutputTokens limits) {
        private MaxOutputRule {
            patterns = List.copyOf(patterns);
            limits = Objects.requireNonNull(limits, "limits");
        }

        private boolean matches(String normalizedModel) {
            return patterns.stream().anyMatch(normalizedModel::contains);
        }
    }

    private record ContextRule(List<String> patterns, ContextWindow window) {
        private ContextRule {
            patterns = List.copyOf(patterns);
            window = Objects.requireNonNull(window, "window");
        }

        private boolean matches(String normalizedModel) {
            return patterns.stream().anyMatch(normalizedModel::contains);
        }
    }
}
