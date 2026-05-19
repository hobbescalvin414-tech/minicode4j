const MUTED = "\x1b[90m";
const DIM = "\x1b[2m";
const BOLD = "\x1b[1m";
const BLUE = "\x1b[94m";
const GOLD = "\x1b[93m";
const GREEN = "\x1b[32m";
const RED = "\x1b[31m";
const RESET = "\x1b[0m";
const CLEAR_PREVIOUS_LINE = "\x1b[1A\x1b[2K";
const PROMPT_DISPLAY_WIDTH = 2;
export class StreamingRenderer {
    lastRendered = null;
    render(event) {
        const rendered = renderTranscriptEvent(event);
        if (rendered.length === 0) {
            return rendered;
        }
        if (event.type === "tool_finished" && this.lastRendered?.type === "tool_started"
            && this.lastRendered.toolUseId === event.toolUseId) {
            this.lastRendered = { type: "other" };
            return CLEAR_PREVIOUS_LINE + rendered;
        }
        this.lastRendered = event.type === "tool_started"
            ? { type: "tool_started", toolUseId: event.toolUseId }
            : { type: "other" };
        return rendered;
    }
}
export function renderEvents(events) {
    return renderTranscriptEvents(events);
}
export function renderTranscriptEvents(events) {
    const finishedToolIds = new Set(events.filter((event) => event.type === "tool_finished").map((event) => event.toolUseId));
    return events
        .map((event) => (event.type === "tool_started" && finishedToolIds.has(event.toolUseId) ? "" : renderTranscriptEvent(event)))
        .filter((line) => line.length > 0)
        .join("\n");
}
export function renderEvent(event) {
    return renderTranscriptEvent(event);
}
export function renderTranscriptEvent(event) {
    switch (event.type) {
        case "ready":
            return muted(`ready ${event.sessionId} ${event.model} ${event.cwd}`);
        case "history_item":
            return event.kind === "user" ? renderUserSubmittedLine(event.text) : `• ${event.text}`;
        case "assistant_message":
            return `• ${event.text}`;
        case "assistant_progress":
            return `◦ ${event.text}`;
        case "tool_started":
            return `◦ ${bold("Running")} ${toolText(event.toolName, event.summary)}`;
        case "tool_finished":
            return renderToolFinished(event);
        case "permission_request":
            return "";
        case "permission_audit":
            return `• Permission ${event.decision === "allowed" ? "allowed" : "denied"}: ${event.summary}`;
        case "await_user":
            return "";
        case "context_stats":
            return "";
        case "status":
            return "";
        case "turn_stop":
            if (event.reason === "FINAL" || event.reason === "AWAIT_USER") {
                return "";
            }
            return event.message == null ? `• ${event.reason}` : `• ${event.message}`;
        case "error":
            return `${event.recoverable ? "◦" : "•"} ${event.recoverable ? muted("recoverable error:") : failedText("fatal error:")} ${event.message}`;
    }
}
export function renderLiveRegion(options) {
    return renderLiveRegionLayout(options).text;
}
export function renderLiveRegionLayout(options) {
    const lines = [];
    const status = renderLiveStatus(options.detailsState);
    if (status.length > 0) {
        lines.push(status);
    }
    const footer = renderLiveFooter(options.detailsState);
    if (footer.length > 0) {
        lines.push(footer);
    }
    if (options.inputState.mode === "permission") {
        lines.push(...renderPermissionLive(options.inputState));
    }
    if (options.inputState.mode === "permission_feedback") {
        lines.push(...renderPermissionFeedbackLive());
    }
    if (options.inputState.mode === "ask_user") {
        lines.push(...renderAskUserLive(options.inputState));
    }
    const promptLineIndex = lines.length;
    lines.push(`${promptForLive(options.inputState)}${goldText(options.line)}`);
    const columns = normalizeColumns(options.columns);
    const rowCounts = lines.map((line) => terminalRowCount(line, columns));
    const terminalRowCountTotal = rowCounts.reduce((sum, count) => sum + count, 0);
    const rowsBeforePrompt = rowCounts.slice(0, promptLineIndex).reduce((sum, count) => sum + count, 0);
    const promptCursorDisplayColumn = PROMPT_DISPLAY_WIDTH + terminalDisplayWidth(options.line.slice(0, options.cursor));
    const promptCursorRow = columns == null ? 0 : Math.floor(promptCursorDisplayColumn / columns);
    const promptColumn = columns == null ? promptCursorDisplayColumn : promptCursorDisplayColumn % columns;
    const cursorRow = rowsBeforePrompt + promptCursorRow;
    return {
        text: lines.join("\n"),
        promptLineIndex,
        promptColumn,
        logicalLineCount: lines.length,
        terminalRowCount: terminalRowCountTotal,
        rowsBelowCursor: Math.max(0, terminalRowCountTotal - cursorRow - 1)
    };
}
export function renderDetailsPanel(state, inputState) {
    return [
        muted("----- session details -----"),
        `session: ${state.sessionLabel}`,
        state.model == null ? "" : `model: ${state.model}`,
        `backend: ${state.backendStatus}`,
        renderContextLine(state),
        renderToolLine(state),
        renderPermissionLine(state),
        renderAskUserLine(state),
        `input mode: ${inputState.mode}`,
        muted("---------------------------")
    ].filter((line) => line.length > 0).join("\n");
}
export function renderUserSubmittedLine(text) {
    return `${BLUE}›${RESET} ${goldText(text)}`;
}
export function renderSubmittedCommandLine(command, rawLine) {
    const text = rawLine.trim();
    switch (command.type) {
        case "user_submit":
            return renderUserSubmittedLine(text);
        case "ask_user_answer":
            return renderUserSubmittedLine(`answer: ${text}`);
        case "permission_response": {
            const feedback = command.feedback?.trim();
            return feedback == null || feedback.length === 0
                ? null
                : renderUserSubmittedLine(`feedback: ${feedback}`);
        }
        case "init":
        case "shutdown":
            return null;
    }
}
function renderToolFinished(event) {
    const preview = event.preview.trim().length === 0
        ? ""
        : "\n" + event.preview
            .split(/\r?\n/)
            .filter((line) => line.length > 0)
            .map((line) => muted(`  | ${line}`))
            .join("\n");
    const hidden = event.truncated ? `\n${muted(`  +${event.hiddenLines} hidden lines (+${event.hiddenLines} lines hidden)`)}` : "";
    const storage = event.storageRef == null ? "" : `\n${muted(`  stored output: ${event.storageRef}`)}`;
    const diff = renderDiffPreview(event.diffPreview);
    const marker = event.status === "ok" ? greenText("•") : failedText("•");
    return `${marker} ${bold("Ran")} ${toolText(event.toolName, event.summary)}${event.status === "ok" ? "" : ` ${failedText(statusLabel(event.status))}`}${preview}${hidden}${storage}${diff}`;
}
function renderLiveStatus(state) {
    if (state.backendStatus === "Ready") {
        return "";
    }
    const text = state.lastStatusText == null || state.lastStatusText.length === 0
        ? state.backendStatus
        : state.lastStatusText;
    return `◦ ${muted(`${text} · ctrl+c to interrupt`)}`;
}
function renderLiveFooter(state) {
    return state.recentContext == null ? muted("context pending") : muted(state.recentContext.badge);
}
function renderPermissionLive(state) {
    return [
        "",
        bold(state.title),
        state.body.length === 0 ? "" : state.body,
        ...state.facts.map((fact) => fact),
        "",
        ...state.choices.map((choice, index) => {
            const prefix = index === state.selectedIndex ? `${BLUE}› ${index + 1}.` : `  ${index + 1}.`;
            return `${prefix} ${permissionDisplayLabel(choice)} ${muted(`(${choice.key})`)}`;
        }),
        muted("Press enter to confirm or esc to cancel")
    ].filter((line) => line.length > 0);
}
function permissionDisplayLabel(choice) {
    switch (choice.key) {
        case "allow_once":
            return "Yes, proceed";
        case "allow_turn":
            return "Yes, and don't ask again this turn";
        case "allow_always":
            return "Yes, and don't ask again";
        case "deny_once":
            return "No, cancel";
        case "deny_always":
            return "No, and don't ask again";
        case "deny_feedback":
            return "No, and tell MiniCode what to do differently";
        default:
            return choice.label;
    }
}
function renderAskUserLive(state) {
    return [
        "",
        bold("Question"),
        state.question
    ];
}
function renderPermissionFeedbackLive() {
    return [
        "",
        bold("Tell MiniCode what to do differently")
    ];
}
function promptForLive(_state) {
    return `${BLUE}›${RESET} `;
}
function renderDiffPreview(diffPreview) {
    if (diffPreview == null) {
        return "";
    }
    const title = diffPreview.title == null || diffPreview.title.length === 0 ? "preview" : diffPreview.title;
    const lines = diffPreview.lines.map(renderDiffLine).join("\n");
    const hidden = diffPreview.truncated === true
        ? `\n${muted(`  +${diffPreview.hiddenLines ?? 0} hidden diff lines`)}`
        : "";
    return `\n  diff ${title}${lines.length > 0 ? `\n${lines}` : ""}${hidden}`;
}
function renderDiffLine(line) {
    const text = `  | ${line}`;
    if (line.startsWith("+") && !line.startsWith("+++")) {
        return greenText(text);
    }
    if (line.startsWith("-") && !line.startsWith("---")) {
        return failedText(text);
    }
    return muted(text);
}
function renderContextLine(state) {
    if (state.recentContext == null) {
        return "context: none";
    }
    const context = state.recentContext;
    return `context: ${context.badge} percent: ${context.percent}% remaining: ${context.remaining} warning: ${context.warning}`;
}
function renderToolLine(state) {
    if (state.recentTool == null) {
        return "tool: none";
    }
    const tool = state.recentTool;
    const storage = tool.storageRef == null ? "storageRef: none" : `storageRef: ${tool.storageRef}`;
    return `tool: ${tool.toolName} ${tool.status} ${tool.summary} truncated: ${tool.truncated ? "yes" : "no"} ${storage}`;
}
function renderPermissionLine(state) {
    if (state.recentPermission == null) {
        return "permission: none";
    }
    const permission = state.recentPermission;
    const choice = permission.choiceKey == null ? "" : ` ${permission.choiceKey}`;
    const summary = permission.summary == null || permission.summary.length === 0 ? "" : ` ${permission.summary}`;
    return `permission: ${permission.status}${choice}${summary}`;
}
function renderAskUserLine(state) {
    if (state.recentAskUser == null) {
        return "ask_user: none";
    }
    return `ask_user: ${state.recentAskUser.status} ${state.recentAskUser.toolUseId}`;
}
function statusLabel(status) {
    return status === "error" || status === "failed" ? "failed" : status;
}
function muted(text) {
    return `${MUTED}${text}${RESET}`;
}
function bold(text) {
    return `${BOLD}${text}${RESET}`;
}
function greenText(text) {
    return `${GREEN}${text}${RESET}`;
}
function failedText(text) {
    return `${RED}${text}${RESET}`;
}
function goldText(text) {
    return `${GOLD}${text}${RESET}`;
}
function toolText(toolName, summary) {
    return `${BLUE}${toolName}${RESET}${summary.length === 0 ? "" : ` ${summary}`}`;
}
function terminalDisplayWidth(text) {
    let width = 0;
    for (const character of stripAnsi(text)) {
        const codePoint = character.codePointAt(0) ?? 0;
        if (codePoint === 0x09) {
            width += 8 - (width % 8);
        }
        else if (isZeroWidthCodePoint(codePoint)) {
            continue;
        }
        else {
            width += isFullWidthCodePoint(codePoint) ? 2 : 1;
        }
    }
    return width;
}
function terminalRowCount(text, columns) {
    if (columns == null) {
        return 1;
    }
    const width = terminalDisplayWidth(text);
    return Math.max(1, Math.ceil(width / columns));
}
function normalizeColumns(columns) {
    return Number.isInteger(columns) && columns != null && columns > 0 ? columns : null;
}
function stripAnsi(text) {
    return text.replace(/\x1b\[[0-9;]*[A-Za-z]/g, "");
}
function isZeroWidthCodePoint(codePoint) {
    return codePoint === 0
        || codePoint < 32
        || (codePoint >= 0x7f && codePoint < 0xa0)
        || (codePoint >= 0x300 && codePoint <= 0x36f)
        || (codePoint >= 0x483 && codePoint <= 0x489)
        || (codePoint >= 0x591 && codePoint <= 0x5bd)
        || codePoint === 0x5bf
        || (codePoint >= 0x5c1 && codePoint <= 0x5c2)
        || (codePoint >= 0x5c4 && codePoint <= 0x5c5)
        || codePoint === 0x5c7
        || (codePoint >= 0x610 && codePoint <= 0x61a)
        || (codePoint >= 0x64b && codePoint <= 0x65f)
        || codePoint === 0x670
        || (codePoint >= 0x6d6 && codePoint <= 0x6dc)
        || (codePoint >= 0x6df && codePoint <= 0x6e4)
        || (codePoint >= 0x6e7 && codePoint <= 0x6e8)
        || (codePoint >= 0x6ea && codePoint <= 0x6ed)
        || (codePoint >= 0x1160 && codePoint <= 0x11ff)
        || (codePoint >= 0x1ab0 && codePoint <= 0x1aff)
        || (codePoint >= 0x1dc0 && codePoint <= 0x1dff)
        || (codePoint >= 0x20d0 && codePoint <= 0x20ff)
        || (codePoint >= 0xfe00 && codePoint <= 0xfe0f)
        || (codePoint >= 0xfe20 && codePoint <= 0xfe2f)
        || codePoint === 0x200d;
}
function isFullWidthCodePoint(codePoint) {
    return codePoint >= 0x1100 && (codePoint <= 0x115f
        || codePoint === 0x2329
        || codePoint === 0x232a
        || (codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint !== 0x303f)
        || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
        || (codePoint >= 0xf900 && codePoint <= 0xfaff)
        || (codePoint >= 0xfe10 && codePoint <= 0xfe19)
        || (codePoint >= 0xfe30 && codePoint <= 0xfe6f)
        || (codePoint >= 0xff00 && codePoint <= 0xff60)
        || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
        || (codePoint >= 0x1f300 && codePoint <= 0x1f64f)
        || (codePoint >= 0x1f900 && codePoint <= 0x1f9ff)
        || (codePoint >= 0x20000 && codePoint <= 0x3fffd));
}
