package minicode.permissions;

import minicode.edit.EditReview;
import minicode.edit.UnifiedDiffBuilder;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.*;
import minicode.permissions.service.PromptingPermissionService;
import minicode.permissions.store.InMemoryPermissionStore;
import minicode.permissions.store.PermissionStore;
import minicode.permissions.store.PermissionStoreDecision;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceTest {
    @Test
    void allowPathReadReturnsGrant() {
        PermissionService service = new PromptingPermissionService(PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE));
        PermissionContext context = context();
        Path path = Path.of("notes.txt");

        PermissionGrant grant = service.ensurePath(path, PathIntent.READ, context);

        assertEquals(PermissionKind.PATH, grant.kind());
        assertEquals(PermissionGrantScope.ONCE, grant.scope());
        assertEquals(new PermissionResource.PathResource(path, PathIntent.READ), grant.resource());
    }

    @Test
    void denyPathReadThrowsPermissionDeniedExceptionWithFeedback() {
        PermissionService service = new PromptingPermissionService(
                PermissionPromptHandler.deny(PermissionDecision.DENY_WITH_FEEDBACK, "Stay in the workspace")
        );

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.ensurePath(Path.of("secret.txt"), PathIntent.READ, context()));

        assertEquals(Optional.of("Stay in the workspace"), exception.feedback());
        assertEquals(PermissionRequestKind.PATH, exception.request().kind());
        assertTrue(exception.getMessage().contains("Stay in the workspace"));
    }

    @Test
    void allowCommandReturnsGrantWithoutExecutingCommand() {
        CapturingPromptHandler promptHandler = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_turn", PermissionDecision.ALLOW_TURN)
        );
        PermissionService service = new PromptingPermissionService(promptHandler);
        CommandSignature signature = new CommandSignature("mvn", List.of("test"));

        PermissionGrant grant = service.ensureCommand(signature, CommandClassification.DEVELOPMENT, context());

        assertEquals(PermissionKind.COMMAND, grant.kind());
        assertEquals(PermissionGrantScope.TURN, grant.scope());
        assertEquals(new PermissionResource.CommandResource(signature, CommandClassification.DEVELOPMENT), grant.resource());
        assertTrue(promptHandler.request.choices().stream()
                .anyMatch(choice -> choice.key().equals("allow_turn")
                        && choice.decision() == PermissionDecision.ALLOW_TURN));
    }

    @Test
    void permissionChoicesDescribeResourceSpecificTurnScope() {
        CapturingPromptHandler commandPrompt = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
        new PromptingPermissionService(commandPrompt).ensureCommand(
                new CommandSignature("cmd", List.of("/c", "echo hi")),
                CommandClassification.READONLY,
                context()
        );

        assertEquals("Allow this command this turn", choiceLabel(commandPrompt.request, "allow_turn"));

        CapturingPromptHandler pathPrompt = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
        new PromptingPermissionService(pathPrompt).ensurePath(Path.of("outside").toAbsolutePath().normalize(),
                PathIntent.READ, context());

        assertEquals("Allow this path this turn", choiceLabel(pathPrompt.request, "allow_turn"));

        CapturingPromptHandler listPrompt = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
        new PromptingPermissionService(listPrompt).ensurePath(Path.of("outside").toAbsolutePath().normalize(),
                PathIntent.LIST, context());

        assertEquals("Allow this directory this turn", choiceLabel(listPrompt.request, "allow_turn"));

        CapturingPromptHandler editPrompt = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
        new PromptingPermissionService(editPrompt).ensureEdit(editResource("void oldName() {}\n",
                "void newName() {}\n"), context());

        assertEquals("Allow this edit target this turn", choiceLabel(editPrompt.request, "allow_turn"));
    }

    @Test
    void missingPromptHandlerDeniesInsteadOfAllowingSilently() {
        PermissionService service = new PromptingPermissionService(PermissionPromptHandler.unavailable());

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.ensureCommand(new CommandSignature("cmd", List.of("/c", "dir")),
                        CommandClassification.READONLY,
                        context()));

        assertTrue(exception.feedback().orElseThrow().contains("No permission prompt handler"));
    }

    @Test
    void denyChoiceThrowsPermissionDeniedException() {
        PermissionService service = new PromptingPermissionService(request ->
                PermissionPromptResult.deny("deny_once", PermissionDecision.DENY_ONCE, null));

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.ensurePath(Path.of("secret.txt"), PathIntent.READ, context()));

        assertEquals(Optional.empty(), exception.feedback());
    }

    @Test
    void denyWithFeedbackChoicePreservesFeedback() {
        PermissionService service = new PromptingPermissionService(request ->
                PermissionPromptResult.deny("deny_feedback", PermissionDecision.DENY_WITH_FEEDBACK, "Use a narrower path"));

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.ensurePath(Path.of("secret.txt"), PathIntent.READ, context()));

        assertEquals(Optional.of("Use a narrower path"), exception.feedback());
        assertEquals(Optional.of("deny_feedback"), exception.choiceKey());
    }

    @Test
    void unknownChoiceKeyIsRejected() {
        PermissionService service = new PromptingPermissionService(request ->
                PermissionPromptResult.allow("missing_choice", PermissionDecision.ALLOW_ONCE));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.ensurePath(Path.of("secret.txt"), PathIntent.READ, context()));

        assertTrue(exception.getMessage().contains("Unknown permission choice"));
    }

    @Test
    void allowOnceReturnsGrantAndDoesNotPersist() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        PermissionService service = new PromptingPermissionService(
                request -> PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE),
                store
        );

        service.ensurePath(Path.of("notes.txt"), PathIntent.READ, context());

        assertTrue(store.entries().isEmpty());
    }

    @Test
    void allowAlwaysPersistsAndSkipsPromptForSamePathResource() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        CountingPromptHandler promptHandler = new CountingPromptHandler(
                PermissionPromptResult.allow("allow_always", PermissionDecision.ALLOW_ALWAYS)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, store);
        Path path = Path.of("notes.txt").toAbsolutePath().normalize();

        service.ensurePath(path, PathIntent.READ, context());
        PermissionGrant secondGrant = service.ensurePath(path, PathIntent.READ, context());

        assertEquals(1, promptHandler.calls);
        assertEquals(PermissionGrantScope.ALWAYS, secondGrant.scope());
        assertEquals(PermissionPersistence.USER, secondGrant.persistence());
        assertEquals(PermissionStoreDecision.ALLOW, store.find(new PermissionResource.PathResource(path, PathIntent.READ))
                .orElseThrow().decision());
    }

    @Test
    void denyAlwaysPersistsAndSkipsPromptForSameCommandResource() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        CountingPromptHandler promptHandler = new CountingPromptHandler(
                PermissionPromptResult.deny("deny_always", PermissionDecision.DENY_ALWAYS, null)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, store);
        CommandSignature signature = new CommandSignature("mvn", List.of("test"));

        assertThrows(PermissionDeniedException.class,
                () -> service.ensureCommand(signature, CommandClassification.DEVELOPMENT, context()));
        assertThrows(PermissionDeniedException.class,
                () -> service.ensureCommand(signature, CommandClassification.DEVELOPMENT, context()));

        assertEquals(1, promptHandler.calls);
        assertEquals(PermissionStoreDecision.DENY, store.find(new PermissionResource.CommandResource(
                signature,
                CommandClassification.DEVELOPMENT
        )).orElseThrow().decision());
    }

    @Test
    void denyOnceDoesNotPersist() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        PermissionService service = new PromptingPermissionService(
                request -> PermissionPromptResult.deny("deny_once", PermissionDecision.DENY_ONCE, null),
                store
        );

        assertThrows(PermissionDeniedException.class,
                () -> service.ensurePath(Path.of("secret.txt"), PathIntent.READ, context()));

        assertTrue(store.entries().isEmpty());
    }

    @Test
    void denyWithFeedbackDoesNotPersistButPreservesFeedback() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        PermissionService service = new PromptingPermissionService(
                request -> PermissionPromptResult.deny("deny_feedback", PermissionDecision.DENY_WITH_FEEDBACK,
                        "Explain why"),
                store
        );

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.ensurePath(Path.of("secret.txt"), PathIntent.READ, context()));

        assertEquals(Optional.of("Explain why"), exception.feedback());
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void ensureEditAllowOnceReturnsGrant() {
        PermissionResource.EditResource resource = editResource("void oldName() {}\n", "void newName() {}\n");
        PermissionService service = new PromptingPermissionService(request ->
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE));

        PermissionGrant grant = service.ensureEdit(resource, context());

        assertEquals(PermissionKind.EDIT, grant.kind());
        assertEquals(PermissionGrantScope.ONCE, grant.scope());
        assertEquals(resource, grant.resource());
        assertEquals(PermissionPersistence.MEMORY, grant.persistence());
    }

    @Test
    void ensureEditDenyWithFeedbackThrowsAndPreservesFeedback() {
        PermissionResource.EditResource resource = editResource("void oldName() {}\n", "void newName() {}\n");
        PermissionService service = new PromptingPermissionService(request ->
                PermissionPromptResult.deny("deny_feedback", PermissionDecision.DENY_WITH_FEEDBACK,
                        "Use a narrower replacement"));

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.ensureEdit(resource, context()));

        assertEquals(PermissionRequestKind.EDIT, exception.request().kind());
        assertEquals(Optional.of("deny_feedback"), exception.choiceKey());
        assertEquals(Optional.of("Use a narrower replacement"), exception.feedback());
    }

    @Test
    void ensureMcpToolUsesConservativeApprovalAndResourceSpecificTurnScope() {
        CapturingPromptHandler promptHandler = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_turn", PermissionDecision.ALLOW_TURN)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, new InMemoryPermissionStore());
        PermissionResource.McpToolResource resource = new PermissionResource.McpToolResource(
                "fake",
                "echo",
                "mcp__fake__echo",
                "Echo text"
        );

        PermissionGrant grant = service.ensureMcpTool(resource, context());

        assertEquals(PermissionKind.MCP_TOOL, grant.kind());
        assertEquals(PermissionGrantScope.TURN, grant.scope());
        assertEquals(resource, grant.resource());
        assertEquals(PermissionRequestKind.MCP_TOOL, promptHandler.request.kind());
        assertEquals("Allow this MCP tool this turn", choiceLabel(promptHandler.request, "allow_turn"));
    }

    @Test
    void ensureEditBuildsReviewRequestDetailsAndChoicesWithoutAllowAlways() {
        PermissionResource.EditResource resource = editResource("void oldName() {}\n", "void newName() {}\n");
        CapturingPromptHandler promptHandler = new CapturingPromptHandler(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
        PermissionService service = new PromptingPermissionService(promptHandler);

        service.ensureEdit(resource, context());

        PermissionRequest request = promptHandler.request;
        assertEquals(PermissionRequestKind.EDIT, request.kind());
        assertTrue(request.feedbackAllowed());
        assertEquals(List.of("allow_once", "allow_turn", "deny_once", "deny_feedback"),
                request.choices().stream().map(PermissionChoice::key).toList());
        assertFalse(request.choices().stream()
                .anyMatch(choice -> choice.decision() == PermissionDecision.ALLOW_ALWAYS));
        String details = String.join("\n", request.details().facts());
        assertTrue(details.contains("Path: src/App.java"));
        assertTrue(details.contains("Operation: OVERWRITE"));
        assertTrue(details.contains("Summary: Rename method"));
        assertTrue(details.contains("Preview truncated: false"));
        assertTrue(details.contains("+void newName() {}"));
    }

    @Test
    void ensureEditAllowTurnReusesSameEditResourceWithinTurnAndExpires() {
        PermissionResource.EditResource resource = editResource("void oldName() {}\n", "void newName() {}\n");
        CountingPromptHandler promptHandler = new CountingPromptHandler(
                PermissionPromptResult.allow("allow_turn", PermissionDecision.ALLOW_TURN)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, new InMemoryPermissionStore());

        service.beginTurn("turn-1");
        service.ensureEdit(resource, context());
        service.ensureEdit(resource, context());
        service.endTurn("turn-1");
        service.ensureEdit(resource, context());

        assertEquals(2, promptHandler.calls);
    }

    @Test
    void ensureEditAllowTurnDoesNotApplyToDifferentReviewInSameTurn() {
        PermissionResource.EditResource first = editResource("void oldName() {}\n", "void newName() {}\n");
        PermissionResource.EditResource second = editResource("int oldValue = 1;\n", "int newValue = 2;\n");
        CountingPromptHandler promptHandler = new CountingPromptHandler(
                PermissionPromptResult.allow("allow_turn", PermissionDecision.ALLOW_TURN)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, new InMemoryPermissionStore());

        service.beginTurn("turn-1");
        service.ensureEdit(first, context());
        service.ensureEdit(second, context());

        assertEquals(2, promptHandler.calls);
    }

    @Test
    void editPermissionStoreKeyIncludesReviewFingerprint() {
        PermissionResource.EditResource first = editResource("void oldName() {}\n", "void newName() {}\n");
        PermissionResource.EditResource second = editResource("int oldValue = 1;\n", "int newValue = 2;\n");
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        store.allow(PermissionKind.EDIT, first);
        CountingPromptHandler promptHandler = new CountingPromptHandler(
                PermissionPromptResult.allow("allow_once", PermissionDecision.ALLOW_ONCE)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, store);

        PermissionGrant grant = service.ensureEdit(second, context());

        assertEquals(1, promptHandler.calls);
        assertEquals(PermissionGrantScope.ONCE, grant.scope());
        assertEquals(PermissionPersistence.MEMORY, grant.persistence());
    }

    @Test
    void allowTurnAppliesWithinTurnAndExpiresAfterEndTurn() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        CountingPromptHandler promptHandler = new CountingPromptHandler(
                PermissionPromptResult.allow("allow_turn", PermissionDecision.ALLOW_TURN)
        );
        PermissionService service = new PromptingPermissionService(promptHandler, store);
        Path path = Path.of("notes.txt").toAbsolutePath().normalize();

        service.beginTurn("turn-1");
        service.ensurePath(path, PathIntent.READ, context());
        service.ensurePath(path, PathIntent.READ, context());
        service.endTurn("turn-1");
        service.ensurePath(path, PathIntent.READ, context());

        assertEquals(2, promptHandler.calls);
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void denyAlwaysBeatsExistingAllowRule() {
        InMemoryPermissionStore store = new InMemoryPermissionStore();
        CommandSignature signature = new CommandSignature("mvn", List.of("test"));
        PermissionResource.CommandResource resource = new PermissionResource.CommandResource(
                signature,
                CommandClassification.DEVELOPMENT
        );
        store.allow(PermissionKind.COMMAND, resource);
        store.deny(PermissionKind.COMMAND, resource);
        PermissionService service = new PromptingPermissionService(request -> fail("prompt should not be called"), store);

        assertThrows(PermissionDeniedException.class,
                () -> service.ensureCommand(signature, CommandClassification.DEVELOPMENT, context()));
    }

    private static PermissionContext context() {
        return new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-use-1"));
    }

    private static PermissionResource.EditResource editResource(String before, String after) {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.OVERWRITE,
                "Rename method",
                Optional.of(before),
                after
        );
        return new PermissionResource.EditResource(review, Optional.of("tool-use-1"));
    }

    private static String choiceLabel(PermissionRequest request, String key) {
        return request.choices().stream()
                .filter(choice -> choice.key().equals(key))
                .findFirst()
                .orElseThrow()
                .label();
    }

    private static final class CapturingPromptHandler implements PermissionPromptHandler {
        private final PermissionPromptResult result;
        private PermissionRequest request;

        private CapturingPromptHandler(PermissionPromptResult result) {
            this.result = result;
        }

        @Override
        public PermissionPromptResult prompt(PermissionRequest request) {
            this.request = request;
            return result;
        }
    }

    private static final class CountingPromptHandler implements PermissionPromptHandler {
        private final PermissionPromptResult result;
        private int calls;

        private CountingPromptHandler(PermissionPromptResult result) {
            this.result = result;
        }

        @Override
        public PermissionPromptResult prompt(PermissionRequest request) {
            calls++;
            return result;
        }
    }
}
