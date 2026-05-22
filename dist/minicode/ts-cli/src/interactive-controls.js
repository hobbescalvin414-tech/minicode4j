export function controlKeyActionForKey(key) {
    if (key?.ctrl === true && key.name?.toLowerCase() === "t") {
        return { type: "render_details" };
    }
    return { type: "none" };
}
export function shouldSendCommandForDetails(command, state) {
    return !(command.type === "user_submit" && state.backendStatus === "Running");
}
export function shouldCloseForBackendEvent(event) {
    return event.type === "error" && !event.recoverable;
}
export function renderPanelDuringReadlineQuestion(options) {
    return `\r\x1b[2K${options.panel}\n${options.prompt}${options.line}`;
}
export function shouldRenderDetailsAt(nowMs, lastRenderedAtMs) {
    return nowMs - lastRenderedAtMs >= 150;
}
export function resetReadlineBuffer(rl) {
    const writable = rl;
    if ("line" in writable) {
        writable.line = "";
    }
    if ("cursor" in writable) {
        writable.cursor = 0;
    }
}
export function disableReadlineHistory(rl) {
    const writable = rl;
    if ("history" in writable) {
        writable.history = [];
    }
    if ("historySize" in writable) {
        writable.historySize = 0;
    }
}
export function liveRegionClearLineCount(previousLineCount, nextLineCount) {
    return Math.max(0, previousLineCount, nextLineCount);
}
export function liveRegionSubmittedClearLineCount(previousLineCount, submittedLineCount) {
    return Math.max(0, previousLineCount, submittedLineCount);
}
export function shouldRefreshLiveRegionAfterReadlineKey(key) {
    const name = key?.name?.toLowerCase();
    if (name == null) {
        return false;
    }
    if (key?.ctrl === true) {
        return false;
    }
    return name.length === 1
        || name === "backspace"
        || name === "delete"
        || name === "left"
        || name === "right"
        || name === "home"
        || name === "end";
}
export function createSigintShutdownHandler(options) {
    let handled = false;
    return () => {
        if (handled) {
            return;
        }
        handled = true;
        options.suppressBackendOutput?.();
        options.sendCommand({ type: "shutdown" });
        options.terminateBackend?.();
        options.closeReadline();
    };
}
