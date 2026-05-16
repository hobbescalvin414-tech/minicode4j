package minicode.permissions.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record PermissionRequest(String requestId, PermissionRequestKind kind, PermissionResource resource,
                                String reason, PermissionRequestDetails details, List<PermissionChoice> choices,
                                boolean feedbackAllowed, PermissionScope scope, PermissionContext context) {
    public PermissionRequest {
        if (Objects.requireNonNull(requestId, "requestId").isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        kind = Objects.requireNonNull(kind, "kind");
        resource = Objects.requireNonNull(resource, "resource");
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        details = Objects.requireNonNull(details, "details");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("permission request must include at least one choice");
        }
        Set<String> keys = new HashSet<>();
        for (PermissionChoice choice : choices) {
            if (!keys.add(choice.key())) {
                throw new IllegalArgumentException("permission choice keys must be unique");
            }
            if (choice.requiresFeedback() && !feedbackAllowed) {
                throw new IllegalArgumentException("feedback choices require feedbackAllowed");
            }
        }
        scope = Objects.requireNonNull(scope, "scope");
        context = Objects.requireNonNull(context, "context");
    }

    public PermissionRequest(String requestId, PermissionRequestKind kind, PermissionResource resource,
                             String reason, Optional<String> toolUseId) {
        this(
                requestId,
                kind,
                resource,
                reason,
                PermissionRequestDetails.of(kind.name(), reason),
                defaultChoices(),
                true,
                PermissionScope.ONCE,
                new PermissionContext("unknown-session", Optional.empty(), Objects.requireNonNull(toolUseId, "toolUseId"))
        );
    }

    public Optional<String> toolUseId() {
        return context.toolUseId();
    }

    public static List<PermissionChoice> defaultChoices() {
        return List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.allowTurn("allow_turn", "Allow for this turn"),
                PermissionChoice.allowAlways("allow_always", "Allow always"),
                PermissionChoice.denyOnce("deny_once", "Deny once"),
                PermissionChoice.denyAlways("deny_always", "Deny always"),
                PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
        );
    }
}
