package minicode.permissions.api;

import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;

import java.util.Objects;

@FunctionalInterface
public interface PermissionPromptHandler {
    PermissionPromptResult prompt(PermissionRequest request);

    static PermissionPromptHandler allow(PermissionDecision decision) {
        PermissionPromptResult result = PermissionPromptResult.allow(decision);
        return request -> result;
    }

    static PermissionPromptHandler deny(PermissionDecision decision, String feedback) {
        PermissionPromptResult result = PermissionPromptResult.deny(decision, feedback);
        return request -> result;
    }

    static PermissionPromptHandler unavailable() {
        return request -> PermissionPromptResult.deny(
                PermissionDecision.DENY_WITH_FEEDBACK,
                "No permission prompt handler is configured for " + Objects.requireNonNull(request, "request").kind()
        );
    }
}
