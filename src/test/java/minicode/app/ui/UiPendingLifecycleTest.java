package minicode.app.ui;

import minicode.permissions.model.CommandClassification;
import minicode.permissions.model.CommandSignature;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;
import minicode.permissions.model.PermissionRequestDetails;
import minicode.permissions.model.PermissionRequestKind;
import minicode.permissions.model.PermissionResource;
import minicode.permissions.model.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPendingLifecycleTest {
    @Test
    void permissionBridgeValidatesRequestChoiceFeedbackAndCleansPendingOnAcceptedResponse() {
        UiPermissionBridge bridge = new UiPermissionBridge();
        bridge.start(commandPermissionRequest());
        assertEquals(Optional.of("tool-1"), bridge.pendingToolUseId());

        UiPermissionBridge.ResponseResult missingFeedback = bridge.handleResponse(
                "request-1", "deny_feedback", Optional.of(""));
        assertTrue(missingFeedback.error().orElseThrow() instanceof UiEvent.Error error && error.recoverable());
        assertTrue(bridge.hasPending());
        assertEquals(Optional.of("tool-1"), bridge.pendingToolUseId());

        UiPermissionBridge.ResponseResult mismatch = bridge.handleResponse(
                "wrong-request", "deny_once", Optional.empty());
        assertTrue(mismatch.error().orElseThrow() instanceof UiEvent.Error error && error.recoverable());
        assertTrue(bridge.hasPending());
        assertEquals(Optional.of("tool-1"), bridge.pendingToolUseId());

        UiPermissionBridge.ResponseResult unknownChoice = bridge.handleResponse(
                "request-1", "allow_forever", Optional.empty());
        assertTrue(unknownChoice.error().orElseThrow() instanceof UiEvent.Error error && error.recoverable());
        assertTrue(bridge.hasPending());

        UiPermissionBridge.ResponseResult result = bridge.handleResponse(
                "request-1", "deny_feedback", Optional.of("Explain first."));

        PermissionPromptResult promptResult = result.promptResult().orElseThrow();
        assertEquals(PermissionDecision.DENY_WITH_FEEDBACK, promptResult.decision());
        assertEquals(Optional.of("deny_feedback"), promptResult.choiceKey());
        assertEquals(Optional.of("Explain first."), promptResult.feedback());
        assertEquals("denied", result.audit().orElseThrow().decision());
        assertFalse(bridge.hasPending());
        assertEquals(Optional.empty(), bridge.pendingToolUseId());
    }

    @Test
    void permissionBridgeRejectsAllowFeedbackAndFatalCleanupClearsPending() {
        UiPermissionBridge bridge = new UiPermissionBridge();
        bridge.start(commandPermissionRequest());

        UiPermissionBridge.ResponseResult allowWithFeedback = bridge.handleResponse(
                "request-1", "allow_once", Optional.of("not allowed"));
        assertTrue(allowWithFeedback.error().orElseThrow() instanceof UiEvent.Error error && error.recoverable());
        assertTrue(bridge.hasPending());

        UiEvent.Error fatal = bridge.clearFatal("backend shutdown");

        assertFalse(fatal.recoverable());
        assertFalse(bridge.hasPending());
    }

    @Test
    void askUserFlowValidatesToolUseIdBlankAnswerAndCleansPendingOnAcceptedAnswer() {
        UiAskUserFlow flow = new UiAskUserFlow();
        UiEvent.AwaitUser awaitUser = flow.start("tool-ask-1", "Which file?");
        assertEquals("tool-ask-1", awaitUser.toolUseId());

        UiAskUserFlow.AnswerResult blank = flow.handleAnswer("tool-ask-1", "   ");
        assertTrue(blank.error().orElseThrow().recoverable());
        assertTrue(flow.hasPending());

        UiAskUserFlow.AnswerResult mismatch = flow.handleAnswer("tool-ask-2", "README.md");
        assertTrue(mismatch.error().orElseThrow().recoverable());
        assertTrue(flow.hasPending());

        UiAskUserFlow.AnswerResult answer = flow.handleAnswer("tool-ask-1", "README.md");
        assertEquals("README.md", answer.answer().orElseThrow().text());
        assertFalse(flow.hasPending());
    }

    @Test
    void askUserFlowReportsRecoverableNoPendingAndFatalCleanup() {
        UiAskUserFlow flow = new UiAskUserFlow();

        UiAskUserFlow.AnswerResult noPending = flow.handleAnswer("tool-ask-1", "README.md");
        assertTrue(noPending.error().orElseThrow().recoverable());

        flow.start("tool-ask-1", "Which file?");
        UiEvent.Error fatal = flow.clearFatal("backend shutdown");

        assertFalse(fatal.recoverable());
        assertFalse(flow.hasPending());
    }

    @Test
    void realBackendSkeletonIsFakeOnlyAndDoesNotExposeRuntimeConfigLoading() {
        UiStdioRealBackend backend = UiStdioRealBackend.fakeOnlySkeleton();

        assertEquals(UiStdioRealBackend.Mode.FAKE_ONLY_SKELETON, backend.mode());
        assertFalse(backend.loadsRuntimeConfig());
        assertFalse(backend.readsApiKeys());
        assertFalse(backend.executesTools());
    }

    private static PermissionRequest commandPermissionRequest() {
        return new PermissionRequest(
                "request-1",
                PermissionRequestKind.COMMAND,
                new PermissionResource.CommandResource(
                        new CommandSignature("mvn", List.of("test")),
                        CommandClassification.SENSITIVE
                ),
                "Allow command execution",
                new PermissionRequestDetails("Command execution", "The model requested command execution.",
                        List.of("Command: mvn test")),
                List.of(
                        PermissionChoice.allowOnce("allow_once", "Allow once"),
                        PermissionChoice.denyOnce("deny_once", "Deny once"),
                        PermissionChoice.denyWithFeedback("deny_feedback", "Deny with feedback")
                ),
                true,
                PermissionScope.ONCE,
                new PermissionContext("session-1", Optional.of("turn-1"), Optional.of("tool-1"))
        );
    }
}
