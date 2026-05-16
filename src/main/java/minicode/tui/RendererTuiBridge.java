package minicode.tui;

import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;

import java.util.Objects;

public final class RendererTuiBridge implements AgentEventSink, PermissionPromptHandler {
    private volatile RendererTuiShell shell;

    void attach(RendererTuiShell shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    @Override
    public void onEvent(AgentEvent event) {
        RendererTuiShell current = shell;
        if (current != null) {
            current.onAgentEvent(event);
        }
    }

    @Override
    public PermissionPromptResult prompt(PermissionRequest request) {
        RendererTuiShell current = shell;
        if (current == null) {
            return PermissionPromptResult.deny(
                    PermissionDecision.DENY_WITH_FEEDBACK,
                    "Renderer permission handler is not attached"
            );
        }
        return current.requestPermission(request);
    }
}
