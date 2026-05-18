import test from "node:test";
import assert from "node:assert/strict";
import {
  initialSessionDetailsState,
  reduceSessionDetailsState
} from "../src/session-details.js";
import { renderDetailsPanel } from "../src/renderer.js";

test("aggregates backend status from UI events", () => {
  let state = initialSessionDetailsState();

  state = reduceSessionDetailsState(state, { type: "ready", sessionId: "ui-session", cwd: "E:\\project", model: "mock" });
  assert.equal(state.sessionLabel, "ui-session");
  assert.equal(state.backendStatus, "Ready");

  state = reduceSessionDetailsState(state, { type: "status", text: "Thinking...", busy: true });
  assert.equal(state.backendStatus, "Running");

  state = reduceSessionDetailsState(state, {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "Approve?",
    facts: [],
    choices: [{ key: "allow_once", label: "Allow once" }]
  });
  assert.equal(state.backendStatus, "Awaiting permission");

  state = reduceSessionDetailsState(state, {
    type: "permission_audit",
    requestId: "req-1",
    decision: "allowed",
    choiceKey: "allow_once",
    summary: "allowed"
  });
  assert.equal(state.backendStatus, "Running");

  state = reduceSessionDetailsState(state, { type: "await_user", toolUseId: "ask-1", question: "Which file?" });
  assert.equal(state.backendStatus, "Awaiting answer");
});

test("details panel includes recent context stats and input mode", () => {
  let state = initialSessionDetailsState();
  state = reduceSessionDetailsState(state, {
    type: "context_stats",
    badge: "context 73% warning 730/1000 provider",
    totalTokens: 730,
    effectiveInput: 1000,
    source: "provider",
    warning: "warning"
  });

  const panel = renderDetailsPanel(state, { mode: "normal" });

  assert.match(panel, /context 73% warning 730\/1000 provider/);
  assert.match(panel, /remaining: 270/);
  assert.match(panel, /input mode: normal/);
});

test("aggregates tool started and finished into recent tool summary", () => {
  let state = initialSessionDetailsState();
  state = reduceSessionDetailsState(state, {
    type: "tool_started",
    toolUseId: "tool-1",
    toolName: "run_command",
    summary: "cmd=\"mvn test\""
  });
  state = reduceSessionDetailsState(state, {
    type: "tool_finished",
    toolUseId: "tool-1",
    toolName: "run_command",
    status: "ok",
    summary: "cmd=\"mvn test\"",
    preview: "BUILD SUCCESS",
    truncated: true,
    hiddenLines: 42,
    storageRef: "stored-output-1"
  });

  const panel = renderDetailsPanel(state, { mode: "normal" });

  assert.match(panel, /tool: run_command ok cmd="mvn test"/);
  assert.match(panel, /truncated: yes/);
  assert.match(panel, /storageRef: stored-output-1/);
});

test("aggregates permission and ask_user states", () => {
  let state = initialSessionDetailsState();
  state = reduceSessionDetailsState(state, {
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "Approve?",
    facts: [],
    choices: [{ key: "allow_once", label: "Allow once" }]
  });
  assert.equal(state.recentPermission?.status, "pending");

  state = reduceSessionDetailsState(state, {
    type: "permission_audit",
    requestId: "req-1",
    decision: "denied",
    choiceKey: "deny_once",
    summary: "denied deny_once"
  });
  assert.equal(state.recentPermission?.status, "denied");

  state = reduceSessionDetailsState(state, { type: "await_user", toolUseId: "ask-1", question: "Which file?" });
  assert.equal(state.recentAskUser?.status, "pending");

  state = reduceSessionDetailsState(state, { type: "turn_stop", reason: "FINAL" });
  assert.equal(state.recentAskUser?.status, "answered");
});

test("details panel does not include sensitive backend internals", () => {
  let state = initialSessionDetailsState();
  state = reduceSessionDetailsState(state, { type: "ready", sessionId: "ui-session", cwd: "E:\\project", model: "mock" });
  const panel = renderDetailsPanel(state, {
    mode: "permission",
    requestId: "req-1",
    title: "Command execution",
    body: "Approve?",
    facts: [],
    selectedIndex: 0,
    choices: []
  });

  assert.doesNotMatch(panel, /system prompt/i);
  assert.doesNotMatch(panel, /api key/i);
  assert.doesNotMatch(panel, /\.jsonl/i);
  assert.doesNotMatch(panel, /ANTHROPIC_AUTH_TOKEN/);
  assert.match(panel, /input mode: permission/);
});
