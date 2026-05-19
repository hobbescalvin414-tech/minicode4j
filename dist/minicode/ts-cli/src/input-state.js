export function initialInputState() {
    return { mode: "normal" };
}
export function reduceInputStateForEvent(state, event) {
    switch (event.type) {
        case "permission_request":
            return {
                mode: "permission",
                requestId: event.requestId,
                title: event.title,
                body: event.body,
                facts: event.facts,
                selectedIndex: 0,
                choices: event.choices
            };
        case "permission_audit":
            return (state.mode === "permission" || state.mode === "permission_feedback") && state.requestId === event.requestId
                ? initialInputState()
                : state;
        case "await_user":
            return { mode: "ask_user", toolUseId: event.toolUseId, question: event.question };
        case "turn_stop":
            return event.reason === "AWAIT_USER" ? state : initialInputState();
        case "error":
            return event.recoverable ? state : initialInputState();
        default:
            return state;
    }
}
export function reduceInputStateForSubmittedCommand(state, command) {
    if (command.type === "permission_response"
        && (state.mode === "permission" || state.mode === "permission_feedback")
        && state.requestId === command.requestId) {
        return initialInputState();
    }
    if (command.type === "ask_user_answer" && state.mode === "ask_user" && state.toolUseId === command.toolUseId) {
        return initialInputState();
    }
    if (command.type === "shutdown") {
        return initialInputState();
    }
    return state;
}
export function reduceInputStateForPermissionSelection(state, delta) {
    if (state.mode !== "permission" || state.choices.length === 0) {
        return state;
    }
    const selectedIndex = positiveModulo(state.selectedIndex + delta, state.choices.length);
    return { ...state, selectedIndex };
}
export function reduceInputStateForEmptySubmit(state) {
    if (state.mode !== "permission") {
        return state;
    }
    const choice = state.choices[state.selectedIndex];
    if (choice == null || !choice.key.toLowerCase().includes("feedback")) {
        return state;
    }
    return {
        mode: "permission_feedback",
        requestId: state.requestId,
        choiceKey: choice.key
    };
}
export function permissionCancelCommand(state) {
    if (state.mode !== "permission") {
        return null;
    }
    const choice = firstChoiceStartingWith(state, "deny_once")
        ?? firstChoiceStartingWith(state, "deny_always")
        ?? state.choices.find((candidate) => candidate.key.toLowerCase().startsWith("deny") && !candidate.key.toLowerCase().includes("feedback"))
        ?? firstChoiceStartingWith(state, "deny_feedback");
    if (choice == null) {
        return null;
    }
    return {
        type: "permission_response",
        requestId: state.requestId,
        choiceKey: choice.key,
        feedback: null
    };
}
export function promptForInputState(state) {
    switch (state.mode) {
        case "normal":
            return "› ";
        case "permission":
            return "› ";
        case "permission_feedback":
            return "› ";
        case "ask_user":
            return "› ";
    }
}
export function commandForInputLine(state, line) {
    const text = line.trim();
    if (isExit(text)) {
        return { type: "shutdown" };
    }
    switch (state.mode) {
        case "normal":
            if (text.length === 0) {
                return null;
            }
            return { type: "user_submit", text };
        case "permission":
            if (text.length === 0) {
                return permissionResponseForSelectedChoice(state);
            }
            return permissionResponseForLine(state, text);
        case "permission_feedback":
            if (text.length === 0) {
                return null;
            }
            return {
                type: "permission_response",
                requestId: state.requestId,
                choiceKey: state.choiceKey,
                feedback: text
            };
        case "ask_user":
            if (text.length === 0) {
                return null;
            }
            return { type: "ask_user_answer", toolUseId: state.toolUseId, text };
    }
}
export function commandForLatestInputLine(getState, line) {
    return commandForInputLine(getState(), line);
}
function permissionResponseForLine(state, text) {
    const normalized = text.toLowerCase();
    const exact = state.choices.find((choice) => choice.key.toLowerCase() === normalized);
    if (exact != null) {
        return {
            type: "permission_response",
            requestId: state.requestId,
            choiceKey: exact.key,
            feedback: null
        };
    }
    if (normalized === "approve" || normalized === "allow" || normalized === "a" || normalized === "y" || normalized === "1") {
        const choice = firstChoiceStartingWith(state, "allow") ?? state.choices[0];
        return {
            type: "permission_response",
            requestId: state.requestId,
            choiceKey: choice.key,
            feedback: null
        };
    }
    if (normalized === "deny" || normalized === "d" || normalized === "n" || normalized === "2") {
        const choice = firstChoiceStartingWith(state, "deny_once") ?? firstChoiceStartingWith(state, "deny") ?? state.choices[0];
        return {
            type: "permission_response",
            requestId: state.requestId,
            choiceKey: choice.key,
            feedback: null
        };
    }
    const feedbackMatch = text.match(/^deny\s+(.+)$/i);
    if (feedbackMatch != null) {
        const choice = firstChoiceStartingWith(state, "deny_feedback") ?? firstChoiceStartingWith(state, "deny");
        return {
            type: "permission_response",
            requestId: state.requestId,
            choiceKey: choice?.key ?? state.choices[0].key,
            feedback: feedbackMatch[1].trim()
        };
    }
    return null;
}
function permissionResponseForSelectedChoice(state) {
    const choice = state.choices[state.selectedIndex] ?? state.choices[0];
    if (choice == null) {
        return null;
    }
    if (choice.key.toLowerCase().includes("feedback")) {
        return null;
    }
    return {
        type: "permission_response",
        requestId: state.requestId,
        choiceKey: choice.key,
        feedback: null
    };
}
function firstChoiceStartingWith(state, prefix) {
    return state.choices.find((choice) => choice.key.toLowerCase().startsWith(prefix));
}
function isExit(text) {
    return text === "exit" || text === "quit" || text === "/exit" || text === "/quit";
}
function positiveModulo(value, divisor) {
    return ((value % divisor) + divisor) % divisor;
}
