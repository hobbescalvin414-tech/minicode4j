import test from "node:test";
import assert from "node:assert/strict";
import {
  StreamingRenderer,
  renderEvents,
  renderLiveRegion,
  renderLiveRegionLayout,
  renderSubmittedCommandLine,
  renderUserSubmittedLine,
  renderTranscriptEvent,
  renderTranscriptEvents
} from "../src/renderer.js";
import type { UiEvent } from "../src/protocol.js";

test("renders all TS-UI-0 mock event kinds", () => {
  const events: UiEvent[] = [
    { type: "ready", sessionId: "mock-session", cwd: "E:\\project", model: "mock" },
    { type: "history_item", id: "history-1", kind: "user", text: "hello" },
    { type: "assistant_message", id: "assistant-1", text: "hi" },
    { type: "assistant_progress", id: "progress-1", text: "working" },
    { type: "tool_started", toolUseId: "tool-1", toolName: "read_file", summary: "path=README.md" },
    {
      type: "tool_finished",
      toolUseId: "tool-1",
      toolName: "read_file",
      status: "ok",
      summary: "path=README.md",
      preview: "FILE: README.md\nbody",
      truncated: true,
      hiddenLines: 7,
      storageRef: null
    },
    {
      type: "permission_request",
      requestId: "req-1",
      title: "Command execution",
      body: "The model requested command execution.",
      facts: ["Command: mvn test"],
      choices: [{ key: "allow_once", label: "Allow once" }]
    },
    { type: "permission_audit", requestId: "req-1", decision: "allowed", choiceKey: "allow_once", summary: "allowed" },
    { type: "await_user", toolUseId: "tool-2", question: "Which file?" },
    { type: "context_stats", badge: "context 5%", totalTokens: 10, effectiveInput: 100, source: "provider", warning: "normal" },
    { type: "status", text: "Ready", busy: false },
    { type: "turn_stop", reason: "FINAL" },
    { type: "turn_stop", reason: "MAX_STEPS", message: "Type continue." }
  ];

  const output = renderEvents(events);
  const plain = stripAnsi(output);

  assert.match(plain, /ready mock-session/);
  assert.match(plain, /› hello/);
  assert.match(plain, /• hi/);
  assert.match(plain, /◦ working/);
  assert.doesNotMatch(output, /Running read_file path=README.md/);
  assert.match(plain, /Ran read_file path=README.md/);
  assert.match(plain, /\+7 lines/);
  assert.match(plain, /Permission allowed: allowed/);
  assert.doesNotMatch(plain, /Command execution/);
  assert.doesNotMatch(plain, /Which file\?/);
  assert.doesNotMatch(plain, /context 5%/);
  assert.doesNotMatch(plain, /status Ready/);
  assert.doesNotMatch(output, /turn_stop FINAL/);
  assert.match(plain, /Type continue\./);
});

test("keeps TS-UI-2 permission and ask_user blocks out of transcript", () => {
  const events: UiEvent[] = [
    {
      type: "permission_request",
      requestId: "req-2",
      title: "Command execution",
      body: "The model requested command execution.",
      facts: ["Command: mvn test", "Classification: BUILD"],
      choices: [
        { key: "allow_once", label: "Allow once" },
        { key: "deny_once", label: "Deny once" }
      ]
    },
    { type: "await_user", toolUseId: "ask-1", question: "Which file should I inspect?" }
  ];

  const output = renderEvents(events);

  assert.equal(output, "");
});

test("merges tool start and finish for the same toolUseId into one stable block", () => {
  const events: UiEvent[] = [
    { type: "tool_started", toolUseId: "tool-1", toolName: "read_file", summary: "path=README.md" },
    {
      type: "tool_finished",
      toolUseId: "tool-1",
      toolName: "read_file",
      status: "ok",
      summary: "path=README.md",
      preview: "FILE: README.md\nbody",
      truncated: false,
      hiddenLines: 0,
      storageRef: null
    }
  ];

  const output = renderEvents(events);
  const plain = stripAnsi(output);

  assert.equal((output.match(/read_file/g) ?? []).length, 1);
  assert.match(plain, /Ran read_file path=README.md/);
  assert.match(output, /\x1b\[90m  \| FILE: README.md\x1b\[0m/);
  assert.doesNotMatch(output, /Running read_file/);
});

test("renders failed, truncated, stored, and diff-preview tool results visibly", () => {
  const events: UiEvent[] = [
    {
      type: "tool_finished",
      toolUseId: "tool-2",
      toolName: "edit_file",
      status: "error",
      summary: "path=README.md",
      preview: "Patch failed: old text not found",
      truncated: true,
      hiddenLines: 12,
      storageRef: "stored-output-1",
      diffPreview: {
        title: "README.md",
        lines: ["--- README.md", "+++ README.md", "-old", "+new"],
        truncated: true,
        hiddenLines: 3
      }
    }
  ];

  const output = renderEvents(events);
  const plain = stripAnsi(output);

  assert.match(plain, /Ran edit_file path=README.md failed/);
  assert.match(output, /Patch failed: old text not found/);
  assert.match(output, /\+12 hidden lines/);
  assert.match(output, /stored-output-1/);
  assert.match(output, /diff README.md/);
  assert.match(output, /\x1b\[31m  \| -old\x1b\[0m/);
  assert.match(output, /\x1b\[32m  \| \+new\x1b\[0m/);
  assert.match(output, /\+3 hidden diff lines/);
});

test("does not render transient turn_stop noise", () => {
  assert.equal(renderEvents([{ type: "turn_stop", reason: "FINAL" }]), "");
  assert.equal(renderEvents([{ type: "turn_stop", reason: "AWAIT_USER" }]), "");
});

test("streaming renderer replaces an immediately previous tool start with the finished block", () => {
  const renderer = new StreamingRenderer();
  const started = renderer.render({
    type: "tool_started",
    toolUseId: "tool-1",
    toolName: "read_file",
    summary: "path=README.md"
  });
  const finished = renderer.render({
    type: "tool_finished",
    toolUseId: "tool-1",
    toolName: "read_file",
    status: "ok",
    summary: "path=README.md",
    preview: "FILE: README.md",
    truncated: false,
    hiddenLines: 0,
    storageRef: null
  });

  assert.match(stripAnsi(started), /Running read_file path=README.md/);
  assert.match(stripAnsi(finished), /Ran read_file path=README.md/);
  assert.match(finished, /^\x1b\[1A\x1b\[2K/);
});

test("streaming renderer still replaces tool start when intervening event is transient", () => {
  const renderer = new StreamingRenderer();
  renderer.render({
    type: "tool_started",
    toolUseId: "tool-1",
    toolName: "read_file",
    summary: "path=README.md"
  });
  renderer.render({ type: "status", text: "Thinking...", busy: true });
  const finished = renderer.render({
    type: "tool_finished",
    toolUseId: "tool-1",
    toolName: "read_file",
    status: "ok",
    summary: "path=README.md",
    preview: "FILE: README.md",
    truncated: false,
    hiddenLines: 0,
    storageRef: null
  });

  assert.match(finished, /^\x1b\[1A\x1b\[2K/);
  assert.match(stripAnsi(finished), /Ran read_file path=README.md/);
});

test("transcript omits transient permission request, ask_user prompt, status, and context", () => {
  const events: UiEvent[] = [
    { type: "status", text: "Thinking...", busy: true },
    { type: "context_stats", badge: "context 5%", totalTokens: 10, effectiveInput: 100, source: "provider", warning: "normal" },
    {
      type: "permission_request",
      requestId: "req-1",
      title: "Command execution",
      body: "Approve?",
      facts: ["Command: mvn test"],
      choices: [{ key: "allow_once", label: "Allow once" }]
    },
    { type: "await_user", toolUseId: "ask-1", question: "Which file?" },
    { type: "permission_audit", requestId: "req-1", decision: "allowed", choiceKey: "allow_once", summary: "allowed allow_once" }
  ];

  const output = renderTranscriptEvents(events);

  assert.doesNotMatch(output, /Thinking/);
  assert.doesNotMatch(output, /context 5%/);
  assert.doesNotMatch(output, /Approve/);
  assert.doesNotMatch(output, /Which file/);
  assert.match(output, /Permission allowed: allowed allow_once/);
});

test("live region renders Codex-style status, permission selector, ask_user, and input prompt", () => {
  const permission = renderLiveRegion({
    detailsState: { backendStatus: "Awaiting permission", lastStatusText: "Working" },
    inputState: {
      mode: "permission",
      requestId: "req-1",
      title: "Would you like to run the following command?",
      body: "Reason: needs test",
      facts: ["$ mvn test"],
      selectedIndex: 0,
      choices: [
        { key: "allow_once", label: "Allow once" },
        { key: "allow_turn", label: "Allow for this turn" },
        { key: "deny_feedback", label: "Deny with feedback" }
      ]
    },
    line: "",
    cursor: 0
  });

  assert.match(permission, /Working/);
  assert.match(permission, /Would you like to run the following command\?/);
  assert.match(permission, /› 1\. Yes, proceed/);
  assert.match(permission, /2\. Yes, and don't ask again this turn/);
  assert.match(permission, /3\. No, and tell MiniCode what to do differently/);
  assert.doesNotMatch(permission, /Codex/);
  assert.match(permission, /Press enter to confirm or esc to cancel/);
  assert.match(permission, /› /);

  const askUser = renderLiveRegion({
    detailsState: { backendStatus: "Awaiting answer" },
    inputState: { mode: "ask_user", toolUseId: "ask-1", question: "Which file should I inspect?" },
    line: "README.md",
    cursor: 9
  });

  assert.match(askUser, /Which file should I inspect\?/);
  assert.match(stripAnsi(askUser), /› README.md/);
});

test("renders user-authored text in gold for transcript and live input separation", () => {
  const history = renderTranscriptEvent({ type: "history_item", id: "history-1", kind: "user", text: "hello" });
  const submitted = renderUserSubmittedLine("/mock tool");
  const live = renderLiveRegion({
    detailsState: { backendStatus: "Ready" },
    inputState: { mode: "normal" },
    line: "hello",
    cursor: 5
  });

  assert.match(history, /\x1b\[93mhello\x1b\[0m/);
  assert.match(submitted, /\x1b\[93m\/mock tool\x1b\[0m/);
  assert.match(live, /\x1b\[93mhello\x1b\[0m/);
  assert.equal(stripAnsi(submitted), "› /mock tool");
  assert.match(stripAnsi(live), /› hello/);
});

test("renders ask_user answers and permission feedback as gold user transcript lines", () => {
  const answer = renderSubmittedCommandLine({ type: "ask_user_answer", toolUseId: "ask-1", text: "123" }, "123");
  const feedback = renderSubmittedCommandLine({
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "deny_feedback",
    feedback: "先解释原因"
  }, "先解释原因");
  const approval = renderSubmittedCommandLine({
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_once",
    feedback: null
  }, "");

  assert.match(answer ?? "", /\x1b\[93manswer: 123\x1b\[0m/);
  assert.equal(stripAnsi(answer ?? ""), "› answer: 123");
  assert.match(feedback ?? "", /\x1b\[93mfeedback: 先解释原因\x1b\[0m/);
  assert.equal(approval, null);
});

test("live region layout keeps prompt as the last line for readline cursor stability", () => {
  const layout = renderLiveRegionLayout({
    detailsState: {
      backendStatus: "Ready",
      recentContext: {
        badge: "context 5% normal 10/100 provider",
        percent: 5,
        totalTokens: 10,
        effectiveInput: 100,
        remaining: 90,
        source: "provider",
        warning: "normal"
      }
    },
    inputState: { mode: "normal" },
    line: "Explain this codebase",
    cursor: 7
  });
  const plain = stripAnsi(layout.text);
  const lines = plain.split("\n");

  assert.equal(layout.promptLineIndex, 1);
  assert.equal(layout.promptColumn, 9);
  assert.equal(lines[0], "context 5% normal 10/100 provider");
  assert.match(lines[1], /^› Explain this codebase/);
});

test("live region layout shows a truthful context placeholder before stats arrive", () => {
  const layout = renderLiveRegionLayout({
    detailsState: { backendStatus: "Ready" },
    inputState: { mode: "normal" },
    line: "",
    cursor: 0
  });
  const plain = stripAnsi(layout.text);

  assert.equal(layout.promptLineIndex, 1);
  assert.equal(plain, "context pending\n› ");
});

test("live region cursor column uses terminal display width for CJK input", () => {
  const layout = renderLiveRegionLayout({
    detailsState: { backendStatus: "Ready" },
    inputState: { mode: "normal" },
    line: "中文abc",
    cursor: 5
  });

  assert.equal(layout.promptColumn, 9);
});

test("live region layout counts wrapped terminal rows instead of only logical lines", () => {
  const layout = renderLiveRegionLayout({
    detailsState: { backendStatus: "Ready" },
    inputState: {
      mode: "permission",
      requestId: "req-1",
      title: "Command execution",
      body: "",
      facts: ["Command: 12345678901234567890"],
      selectedIndex: 0,
      choices: [{ key: "allow_once", label: "Allow once" }]
    },
    line: "",
    cursor: 0,
    columns: 10
  });

  assert.equal(layout.logicalLineCount, stripAnsi(layout.text).split("\n").length);
  assert.ok(layout.terminalRowCount > layout.logicalLineCount);
  assert.equal(layout.rowsBelowCursor, 0);
});

test("transcript renderer skips permission_request and await_user while keeping audit summary", () => {
  assert.equal(renderTranscriptEvent({
    type: "permission_request",
    requestId: "req-1",
    title: "Command execution",
    body: "Approve?",
    facts: [],
    choices: []
  }), "");
  assert.equal(renderTranscriptEvent({ type: "await_user", toolUseId: "ask-1", question: "Which file?" }), "");
  assert.equal(
    renderTranscriptEvent({ type: "permission_audit", requestId: "req-1", decision: "denied", choiceKey: "deny_feedback", summary: "denied deny_feedback" }),
    "• Permission denied: denied deny_feedback"
  );
});

function stripAnsi(text: string): string {
  return text.replace(/\x1b\[[0-9;]*[A-Za-z]/g, "");
}
