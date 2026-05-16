package minicode.tui;

import minicode.edit.EditReview;
import minicode.edit.UnifiedDiffBuilder;
import minicode.permissions.model.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConsolePermissionPromptHandlerTest {
    @Test
    void rendersChoicesFromRequestWithoutHardCodedBusinessOptions() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("x\n".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(request(List.of(
                PermissionChoice.allowOnce("go", "Proceed"),
                PermissionChoice.denyOnce("x", "Stop")
        ), false));

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("1) Proceed [go]"));
        assertTrue(rendered.contains("2) Stop [x]"));
        assertFalse(rendered.contains("[y/N]"));
        assertEquals(Optional.of("x"), result.choiceKey());
        assertEquals(PermissionDecision.DENY_ONCE, result.decision());
    }

    @Test
    void collectsFeedbackOnlyWhenSelectedChoiceRequiresFeedback() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("f\nNeed narrower scope\n".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(request(List.of(
                PermissionChoice.allowOnce("a", "Allow once"),
                PermissionChoice.denyWithFeedback("f", "Deny with feedback")
        ), true));

        assertEquals(Optional.of("f"), result.choiceKey());
        assertEquals(PermissionDecision.DENY_WITH_FEEDBACK, result.decision());
        assertEquals(Optional.of("Need narrower scope"), result.feedback());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Feedback:"));
    }

    @Test
    void acceptsNumberBracketedKeyAndLabelAliases() {
        assertChoiceInput("1\n", PermissionDecision.ALLOW_ONCE);
        assertChoiceInput("[allow_once]\n", PermissionDecision.ALLOW_ONCE);
        assertChoiceInput("Allow once\n", PermissionDecision.ALLOW_ONCE);
        assertChoiceInput("allow_once\n", PermissionDecision.ALLOW_ONCE);
    }

    @Test
    void lineModePromptDoesNotAdvertiseArrowKeySupport() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("deny_once\n".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(request(List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.denyOnce("deny_once", "Deny once")
        ), false));

        assertEquals(Optional.of("deny_once"), result.choiceKey());
        assertEquals(PermissionDecision.DENY_ONCE, result.decision());
        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("Up/Down"), rendered);
        assertFalse(rendered.contains("Esc to deny once"), rendered);
    }

    @Test
    void escapeSelectsDenyOnceWhenAvailable() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("\u001B".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(request(List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.denyOnce("deny_once", "Deny once"),
                PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
        ), true));

        assertEquals(Optional.of("deny_once"), result.choiceKey());
        assertEquals(PermissionDecision.DENY_ONCE, result.decision());
        assertTrue(result.feedback().isEmpty());
    }

    @Test
    void unknownChoicePromptsAgainInsteadOfDenyingSilently() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("abc\nAllow once\n".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(request(List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.denyOnce("deny_once", "Deny once")
        ), false));

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertEquals(PermissionDecision.ALLOW_ONCE, result.decision());
        assertTrue(rendered.contains("Waiting for permission choice"));
        assertTrue(rendered.contains("Unknown permission choice"));
        assertTrue(rendered.contains("1) Allow once [allow_once]"));
    }

    @Test
    void editReviewPromptRendersDiffPreview() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("allow_once\n".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(editRequest());

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("permission: Edit review"));
        assertTrue(rendered.contains("Path: src/App.java"));
        assertTrue(rendered.contains("Operation: OVERWRITE"));
        assertTrue(rendered.contains("Diff preview:"));
        assertTrue(rendered.contains("-void oldName() {}"));
        assertTrue(rendered.contains("+void newName() {}"));
        assertEquals(PermissionDecision.ALLOW_ONCE, result.decision());
    }

    @Test
    void editDenyWithFeedbackReadsFeedback() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream("deny_feedback\nUse a narrower edit\n".getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(editRequest());

        assertEquals(Optional.of("deny_feedback"), result.choiceKey());
        assertEquals(PermissionDecision.DENY_WITH_FEEDBACK, result.decision());
        assertEquals(Optional.of("Use a narrower edit"), result.feedback());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Feedback:"));
    }

    private static PermissionRequest request(List<PermissionChoice> choices, boolean feedbackAllowed) {
        return new PermissionRequest(
                "request-1",
                PermissionRequestKind.PATH,
                new PermissionResource.PathResource(Path.of("secret.txt"), PathIntent.READ),
                "Allow path READ access",
                PermissionRequestDetails.of("Read file", "A file read requires approval"),
                choices,
                feedbackAllowed,
                PermissionScope.ONCE,
                new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-use-1"))
        );
    }

    private static void assertChoiceInput(String input, PermissionDecision expectedDecision) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output
        );

        PermissionPromptResult result = handler.prompt(request(List.of(
                PermissionChoice.allowOnce("allow_once", "Allow once"),
                PermissionChoice.denyOnce("deny_once", "Deny once")
        ), false));

        assertEquals(expectedDecision, result.decision());
    }

    private static PermissionRequest editRequest() {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.OVERWRITE,
                "Rename method",
                Optional.of("void oldName() {}\n"),
                "void newName() {}\n"
        );
        return new PermissionRequest(
                "request-1",
                PermissionRequestKind.EDIT,
                new PermissionResource.EditResource(review, Optional.of("tool-use-1")),
                "Allow file edit",
                new PermissionRequestDetails(
                        "Edit review",
                        "Review the proposed file change before it is applied.",
                        List.of(
                                "Path: src/App.java",
                                "Operation: OVERWRITE",
                                "Summary: Rename method",
                                "Diff preview:",
                                review.diffPreview()
                        )
                ),
                List.of(
                        PermissionChoice.allowOnce("allow_once", "Allow this edit"),
                        PermissionChoice.allowTurn("allow_turn", "Allow edits this turn"),
                        PermissionChoice.denyOnce("deny_once", "Deny"),
                        PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
                ),
                true,
                PermissionScope.ONCE,
                new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-use-1"))
        );
    }
}
