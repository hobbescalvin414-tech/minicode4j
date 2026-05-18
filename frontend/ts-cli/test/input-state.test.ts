import test from "node:test";
import assert from "node:assert/strict";
import {
  commandForInputLine,
  commandForLatestInputLine,
  initialInputState,
  permissionCancelCommand,
  reduceInputStateForEvent,
  reduceInputStateForEmptySubmit,
  reduceInputStateForPermissionSelection,
  reduceInputStateForSubmittedCommand,
  promptForInputState
} from "../src/input-state.js";

test("permission_request switches input mode and approve sends permission_response", () => {
  let state = initialInputState();
  state = reduceInputStateForEvent(state, {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: ["Command: mvn test"],
    choices: [
      { key: "allow_once", label: "Allow once" },
      { key: "deny_once", label: "Deny once" }
    ]
  });

  assert.equal(state.mode, "permission");
  assert.equal(state.title, "Command execution");
  assert.deepEqual(state.facts, ["Command: mvn test"]);
  assert.equal(promptForInputState(state), "› ");
  assert.deepEqual(commandForInputLine(state, "approve"), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_once",
    feedback: null
  });
});

test("permission mode enter submits selected choice and selection can move", () => {
  let state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: ["Command: mvn test"],
    choices: [
      { key: "allow_once", label: "Allow once" },
      { key: "allow_turn", label: "Allow for this turn" },
      { key: "deny_feedback", label: "Deny with feedback" }
    ]
  });

  assert.equal(state.mode, "permission");
  assert.equal(state.selectedIndex, 0);
  assert.deepEqual(commandForInputLine(state, ""), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_once",
    feedback: null
  });

  state = reduceInputStateForPermissionSelection(state, 1);
  if (state.mode !== "permission") {
    assert.fail("expected permission mode");
  }
  assert.equal(state.selectedIndex, 1);
  assert.deepEqual(commandForInputLine(state, ""), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_turn",
    feedback: null
  });

  state = reduceInputStateForPermissionSelection(state, 1);
  if (state.mode !== "permission") {
    assert.fail("expected permission mode");
  }
  assert.equal(state.selectedIndex, 2);

  assert.equal(commandForInputLine(state, ""), null);

  state = reduceInputStateForEmptySubmit(state);
  assert.equal(state.mode, "permission_feedback");
  assert.deepEqual(commandForInputLine(state, "请先解释原因"), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "deny_feedback",
    feedback: "请先解释原因"
  });
});

test("empty submit outside selected feedback keeps input state unchanged", () => {
  const state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: [],
    choices: [
      { key: "allow_once", label: "Allow once" },
      { key: "deny_once", label: "Deny once" }
    ]
  });

  assert.deepEqual(reduceInputStateForEmptySubmit(state), state);
});

test("permission escape command picks the best non-feedback deny choice", () => {
  const state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: [],
    choices: [
      { key: "allow_once", label: "Allow once" },
      { key: "deny_once", label: "Deny once" },
      { key: "deny_feedback", label: "Deny with feedback" }
    ]
  });

  assert.deepEqual(permissionCancelCommand(state), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "deny_once",
    feedback: null
  });
});

test("permission mode supports deny feedback command", () => {
  const state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: ["Command: mvn test"],
    choices: [
      { key: "allow_once", label: "Allow once" },
      { key: "deny_once", label: "Deny once" },
      { key: "deny_feedback", label: "Deny with feedback" }
    ]
  });

  assert.deepEqual(commandForInputLine(state, "deny Please explain first."), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "deny_feedback",
    feedback: "Please explain first."
  });
});

test("await_user switches answer mode and sends ask_user_answer", () => {
  let state = initialInputState();
  state = reduceInputStateForEvent(state, {
    type: "await_user",
    toolUseId: "tool-2",
    question: "Which file?"
  });

  assert.equal(state.mode, "ask_user");
  assert.equal(state.question, "Which file?");
  assert.equal(promptForInputState(state), "› ");
  assert.deepEqual(commandForInputLine(state, "Use README.md"), {
    type: "ask_user_answer",
    toolUseId: "tool-2",
    text: "Use README.md"
  });
});

test("accepted permission and ask_user submissions clear transient input locally", () => {
  let state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "Approve?",
    facts: [],
    choices: [{ key: "allow_once", label: "Allow once" }]
  });
  state = reduceInputStateForSubmittedCommand(state, {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_once",
    feedback: null
  });
  assert.equal(state.mode, "normal");

  state = reduceInputStateForEvent(initialInputState(), {
    type: "await_user",
    toolUseId: "ask-1",
    question: "Which file?"
  });
  state = reduceInputStateForSubmittedCommand(state, {
    type: "ask_user_answer",
    toolUseId: "ask-1",
    text: "README.md"
  });
  assert.equal(state.mode, "normal");
});

test("permission audit and final turn restore normal input mode", () => {
  let state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: [],
    choices: [{ key: "allow_once", label: "Allow once" }]
  });

  state = reduceInputStateForEvent(state, {
    type: "permission_audit",
    requestId: "req-1",
    decision: "allowed",
    choiceKey: "allow_once",
    summary: "allowed"
  });
  assert.equal(state.mode, "normal");

  state = reduceInputStateForEvent(state, {
    type: "turn_stop",
    reason: "FINAL"
  });
  assert.equal(state.mode, "normal");
  assert.equal(promptForInputState(state), "› ");
});

test("ask_user mode restores normal input mode on final turn stop", () => {
  let state = reduceInputStateForEvent(initialInputState(), {
    type: "await_user",
    toolUseId: "ask-1",
    question: "Which file?"
  });

  assert.equal(state.mode, "ask_user");

  state = reduceInputStateForEvent(state, {
    type: "turn_stop",
    reason: "FINAL"
  });

  assert.equal(state.mode, "normal");
  assert.equal(promptForInputState(state), "› ");
});

test("recoverable errors keep pending permission and ask_user input modes", () => {
  let state = reduceInputStateForEvent(initialInputState(), {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: [],
    choices: [{ key: "allow_once", label: "Allow once" }]
  });

  state = reduceInputStateForEvent(state, {
    type: "error",
    message: "Unknown permission choice",
    recoverable: true
  });

  assert.equal(state.mode, "permission");
  assert.equal(state.requestId, "req-1");

  state = reduceInputStateForEvent(initialInputState(), {
    type: "await_user",
    toolUseId: "ask-1",
    question: "Which file?"
  });
  state = reduceInputStateForEvent(state, {
    type: "error",
    message: "ask_user_answer text must not be blank",
    recoverable: true
  });

  assert.equal(state.mode, "ask_user");
  assert.equal(state.toolUseId, "ask-1");
});

test("command parsing uses latest input mode at submit time", () => {
  let state = initialInputState();
  state = reduceInputStateForEvent(state, {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "The model requested command execution.",
    facts: [],
    choices: [{ key: "allow_once", label: "Allow once" }]
  });

  assert.deepEqual(commandForLatestInputLine(() => state, "allow_once"), {
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_once",
    feedback: null
  });

  state = reduceInputStateForEvent(initialInputState(), {
    type: "await_user",
    toolUseId: "ask-1",
    question: "Which file?"
  });

  assert.deepEqual(commandForLatestInputLine(() => state, "Use README.md"), {
    type: "ask_user_answer",
    toolUseId: "ask-1",
    text: "Use README.md"
  });
});
