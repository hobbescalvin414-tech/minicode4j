package minicode.app.ui;

import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;
import minicode.permissions.model.PermissionResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class UiPermissionBridge {
    private static final int MAX_FACTS = 12;
    private static final int MAX_DIFF_LINES = 8;
    private PermissionRequest pending;

    public UiEvent.PermissionRequest start(PermissionRequest request) {
        pending = Objects.requireNonNull(request, "request");
        return new UiEvent.PermissionRequest(
                request.requestId(),
                UiSafeText.redact(request.details().title()),
                UiSafeText.redact(request.details().body()),
                facts(request),
                request.choices().stream()
                        .map(choice -> new UiEvent.PermissionChoice(choice.key(), choice.label()))
                        .toList()
        );
    }

    public ResponseResult handleResponse(String requestId, String choiceKey, Optional<String> feedback) {
        if (pending == null) {
            return ResponseResult.error("No pending permission request.");
        }
        if (!pending.requestId().equals(requestId)) {
            return ResponseResult.error("Permission response requestId does not match pending request.");
        }
        Optional<PermissionChoice> choice = pending.choices().stream()
                .filter(candidate -> candidate.key().equals(choiceKey))
                .findFirst();
        if (choice.isEmpty()) {
            return ResponseResult.error("Unknown permission choice: " + choiceKey);
        }
        PermissionChoice actualChoice = choice.orElseThrow();
        Optional<String> actualFeedback = feedback.map(String::trim).filter(value -> !value.isBlank());
        if (actualChoice.requiresFeedback() && actualFeedback.isEmpty()) {
            return ResponseResult.error("Permission choice requires feedback: " + choiceKey);
        }
        if (isAllow(actualChoice.decision()) && actualFeedback.isPresent()) {
            return ResponseResult.error("Allow permission choices cannot carry feedback.");
        }

        PermissionPromptResult promptResult = isAllow(actualChoice.decision())
                ? PermissionPromptResult.allow(actualChoice.key(), actualChoice.decision())
                : PermissionPromptResult.deny(actualChoice.key(), actualChoice.decision(), actualFeedback.orElse(null));
        String decision = promptResult.allowed() ? "allowed" : "denied";
        UiEvent.PermissionAudit audit = new UiEvent.PermissionAudit(
                pending.requestId(),
                decision,
                actualChoice.key(),
                decision + " " + actualChoice.key()
        );
        pending = null;
        return new ResponseResult(Optional.of(promptResult), Optional.of(audit), Optional.empty());
    }

    public boolean hasPending() {
        return pending != null;
    }

    public Optional<String> pendingToolUseId() {
        return pending == null ? Optional.empty() : pending.context().toolUseId();
    }

    public UiEvent.Error clearFatal(String message) {
        pending = null;
        return new UiEvent.Error(message, false);
    }

    private static List<String> facts(PermissionRequest request) {
        if (request.resource() instanceof PermissionResource.EditResource editResource) {
            return editFacts(editResource);
        }
        return request.details().facts().stream()
                .limit(MAX_FACTS)
                .map(UiSafeText::redact)
                .toList();
    }

    private static List<String> editFacts(PermissionResource.EditResource editResource) {
        List<String> facts = new ArrayList<>();
        facts.add("Path: " + UiSafeText.redact(editResource.path().normalize().toString().replace('\\', '/')));
        facts.add("Operation: " + editResource.operation());
        facts.add("Summary: " + UiSafeText.redact(editResource.summary()));
        facts.add("Before chars: " + editResource.beforeChars());
        facts.add("After chars: " + editResource.afterChars());
        UiEvent.DiffPreview preview = UiDiffPreviewFactory.fromDiff(
                editResource.path().getFileName() == null ? "diff" : editResource.path().getFileName().toString(),
                editResource.diffPreview(),
                MAX_DIFF_LINES
        );
        facts.add("Diff preview:");
        facts.addAll(preview.lines());
        if (preview.truncated()) {
            facts.add("+" + preview.hiddenLines() + " hidden diff lines");
        }
        return List.copyOf(facts);
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }

    public record ResponseResult(Optional<PermissionPromptResult> promptResult,
                                 Optional<UiEvent.PermissionAudit> audit,
                                 Optional<UiEvent.Error> error) {
        public ResponseResult {
            promptResult = Objects.requireNonNull(promptResult, "promptResult");
            audit = Objects.requireNonNull(audit, "audit");
            error = Objects.requireNonNull(error, "error");
        }

        private static ResponseResult error(String message) {
            return new ResponseResult(Optional.empty(), Optional.empty(),
                    Optional.of(new UiEvent.Error(message, true)));
        }
    }
}
