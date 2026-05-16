package minicode.permissions.model;

import java.util.Objects;

public record PermissionChoice(String key, String label, PermissionDecision decision, boolean requiresFeedback) {
    public PermissionChoice {
        if (Objects.requireNonNull(key, "key").isBlank()) {
            throw new IllegalArgumentException("choice key must not be blank");
        }
        if (Objects.requireNonNull(label, "label").isBlank()) {
            throw new IllegalArgumentException("choice label must not be blank");
        }
        decision = Objects.requireNonNull(decision, "decision");
        if (requiresFeedback && decision != PermissionDecision.DENY_WITH_FEEDBACK) {
            throw new IllegalArgumentException("only deny with feedback choices can require feedback");
        }
    }

    public static PermissionChoice allowOnce(String key, String label) {
        return new PermissionChoice(key, label, PermissionDecision.ALLOW_ONCE, false);
    }

    public static PermissionChoice allowTurn(String key, String label) {
        return new PermissionChoice(key, label, PermissionDecision.ALLOW_TURN, false);
    }

    public static PermissionChoice allowAlways(String key, String label) {
        return new PermissionChoice(key, label, PermissionDecision.ALLOW_ALWAYS, false);
    }

    public static PermissionChoice denyOnce(String key, String label) {
        return new PermissionChoice(key, label, PermissionDecision.DENY_ONCE, false);
    }

    public static PermissionChoice denyAlways(String key, String label) {
        return new PermissionChoice(key, label, PermissionDecision.DENY_ALWAYS, false);
    }

    public static PermissionChoice denyWithFeedback(String key, String label) {
        return new PermissionChoice(key, label, PermissionDecision.DENY_WITH_FEEDBACK, true);
    }
}
