package minicode.permissions;

import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPromptResultTest {
    @Test
    void denyWithFeedbackRetainsFeedback() {
        PermissionPromptResult result = PermissionPromptResult.deny(
                "deny_feedback",
                PermissionDecision.DENY_WITH_FEEDBACK,
                "Use a safer command"
        );

        assertEquals(PermissionDecision.DENY_WITH_FEEDBACK, result.decision());
        assertEquals(Optional.of("deny_feedback"), result.choiceKey());
        assertEquals(Optional.of("Use a safer command"), result.feedback());
    }

    @Test
    void denyWithFeedbackRequiresFeedback() {
        assertThrows(IllegalArgumentException.class, () -> PermissionPromptResult.deny(
                "deny_feedback",
                PermissionDecision.DENY_WITH_FEEDBACK,
                ""
        ));
    }

    @Test
    void allowCannotCarryFeedback() {
        assertThrows(IllegalArgumentException.class, () -> PermissionPromptResult.create(
                PermissionDecision.ALLOW_ONCE,
                Optional.of("allow_once"),
                Optional.of("not allowed on allow")
        ));
    }

    @Test
    void choiceKeyMustNotBeBlankWhenPresent() {
        assertThrows(IllegalArgumentException.class, () -> PermissionPromptResult.create(
                PermissionDecision.DENY_ONCE,
                Optional.of(" "),
                Optional.empty()
        ));
    }
}
