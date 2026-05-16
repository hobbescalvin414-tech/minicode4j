package minicode.permissions.model;

import java.util.Objects;
import java.util.Optional;

public record PermissionPromptResult(PermissionDecision decision, Optional<String> choiceKey,
                                     Optional<String> feedback) {
    public PermissionPromptResult {
        decision = Objects.requireNonNull(decision, "decision");
        choiceKey = Objects.requireNonNull(choiceKey, "choiceKey");
        feedback = Objects.requireNonNull(feedback, "feedback");
        choiceKey.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("choice key must not be blank");
            }
        });
        if (decision == PermissionDecision.DENY_WITH_FEEDBACK
                && feedback.filter(value -> !value.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("deny with feedback requires feedback");
        }
        if (isAllow(decision) && feedback.isPresent()) {
            throw new IllegalArgumentException("allow decisions cannot carry feedback");
        }
    }

    public PermissionPromptResult(PermissionDecision decision, Optional<String> feedback) {
        this(decision, Optional.empty(), feedback);
    }

    public static PermissionPromptResult create(PermissionDecision decision, Optional<String> choiceKey,
                                                Optional<String> feedback) {
        return new PermissionPromptResult(decision, choiceKey, feedback);
    }

    public static PermissionPromptResult create(PermissionDecision decision, Optional<String> feedback) {
        return new PermissionPromptResult(decision, Optional.empty(), feedback);
    }

    public static PermissionPromptResult allow(String choiceKey, PermissionDecision decision) {
        if (!isAllow(decision)) {
            throw new IllegalArgumentException("allow factory requires allow decision");
        }
        return new PermissionPromptResult(decision, Optional.of(choiceKey), Optional.empty());
    }

    public static PermissionPromptResult allow(PermissionDecision decision) {
        if (!isAllow(decision)) {
            throw new IllegalArgumentException("allow factory requires allow decision");
        }
        return new PermissionPromptResult(decision, Optional.empty(), Optional.empty());
    }

    public static PermissionPromptResult deny(String choiceKey, PermissionDecision decision, String feedback) {
        return new PermissionPromptResult(decision, Optional.of(choiceKey), Optional.ofNullable(feedback));
    }

    public static PermissionPromptResult deny(PermissionDecision decision, String feedback) {
        return new PermissionPromptResult(decision, Optional.empty(), Optional.ofNullable(feedback));
    }

    public boolean allowed() {
        return isAllow(decision);
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }
}
