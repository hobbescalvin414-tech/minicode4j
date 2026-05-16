package minicode.permissions;

import minicode.edit.EditReview;
import minicode.edit.UnifiedDiffBuilder;
import minicode.permissions.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRequestTest {
    @Test
    void requestCarriesDetailsChoicesScopeAndContextForRendering() {
        PermissionContext context = new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-1"));
        PermissionChoice allow = PermissionChoice.allowOnce("a", "Allow once");
        PermissionChoice deny = PermissionChoice.denyWithFeedback("f", "Deny with feedback");
        PermissionRequestDetails details = new PermissionRequestDetails(
                "Read outside workspace",
                "The model wants to inspect a file.",
                List.of("Path: secret.txt")
        );

        PermissionRequest request = new PermissionRequest(
                "request-1",
                PermissionRequestKind.PATH,
                new PermissionResource.PathResource(Path.of("secret.txt"), PathIntent.READ),
                "Allow path READ access",
                details,
                List.of(allow, deny),
                true,
                PermissionScope.ONCE,
                context
        );

        assertEquals(details, request.details());
        assertEquals(List.of(allow, deny), request.choices());
        assertTrue(request.feedbackAllowed());
        assertEquals(PermissionScope.ONCE, request.scope());
        assertEquals(context, request.context());
        assertEquals(Optional.of("tool-1"), request.toolUseId());
    }

    @Test
    void requestRequiresAtLeastOneChoice() {
        PermissionContext context = new PermissionContext("session-1", Optional.empty(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new PermissionRequest(
                "request-1",
                PermissionRequestKind.COMMAND,
                new PermissionResource.CommandResource(
                        new CommandSignature("mvn", List.of("test")),
                        CommandClassification.DEVELOPMENT
                ),
                "Allow command execution",
                PermissionRequestDetails.of("Command", "Run mvn test"),
                List.of(),
                false,
                PermissionScope.ONCE,
                context
        ));
    }

    @Test
    void requestRejectsFeedbackChoiceWhenFeedbackIsDisabled() {
        PermissionContext context = new PermissionContext("session-1", Optional.empty(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new PermissionRequest(
                "request-1",
                PermissionRequestKind.PATH,
                new PermissionResource.PathResource(Path.of("secret.txt"), PathIntent.READ),
                "Allow path READ access",
                PermissionRequestDetails.of("Path", "Read secret.txt"),
                List.of(PermissionChoice.denyWithFeedback("feedback", "Deny with feedback")),
                false,
                PermissionScope.ONCE,
                context
        ));
    }

    @Test
    void editRequestCarriesReviewDetailsAndFeedbackChoice() {
        PermissionContext context = new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-1"));
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.OVERWRITE,
                "Rename method",
                Optional.of("void oldName() {}\n"),
                "void newName() {}\n"
        );
        PermissionRequestDetails details = new PermissionRequestDetails(
                "Edit review",
                "Review the proposed file change before it is applied.",
                List.of(
                        "Path: src/App.java",
                        "Operation: OVERWRITE",
                        "Summary: Rename method",
                        "Diff preview:",
                        review.diffPreview()
                )
        );
        PermissionChoice feedbackChoice = PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback");

        PermissionRequest request = new PermissionRequest(
                "request-1",
                PermissionRequestKind.EDIT,
                new PermissionResource.EditResource(review, Optional.of("tool-1")),
                "Allow file edit",
                details,
                List.of(
                        PermissionChoice.allowOnce("allow_once", "Allow this edit"),
                        PermissionChoice.allowTurn("allow_turn", "Allow edits this turn"),
                        PermissionChoice.denyOnce("deny_once", "Deny"),
                        feedbackChoice
                ),
                true,
                PermissionScope.ONCE,
                context
        );

        assertTrue(request.feedbackAllowed());
        assertTrue(request.choices().contains(feedbackChoice));
        assertEquals(PermissionRequestKind.EDIT, request.kind());
        assertTrue(String.join("\n", request.details().facts()).contains("Path: src/App.java"));
        assertTrue(String.join("\n", request.details().facts()).contains("Operation: OVERWRITE"));
        assertTrue(String.join("\n", request.details().facts()).contains("Summary: Rename method"));
        assertTrue(String.join("\n", request.details().facts()).contains("+void newName() {}"));
    }

    @Test
    void choiceKeysMustBeUnique() {
        PermissionContext context = new PermissionContext("session-1", Optional.empty(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new PermissionRequest(
                "request-1",
                PermissionRequestKind.PATH,
                new PermissionResource.PathResource(Path.of("secret.txt"), PathIntent.READ),
                "Allow path READ access",
                PermissionRequestDetails.of("Path", "Read secret.txt"),
                List.of(
                        PermissionChoice.allowOnce("same", "Allow once"),
                        PermissionChoice.denyOnce("same", "Deny once")
                ),
                false,
                PermissionScope.ONCE,
                context
        ));
    }
}
