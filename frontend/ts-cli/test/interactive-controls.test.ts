import test from "node:test";
import assert from "node:assert/strict";
import {
  controlKeyActionForKey,
  disableReadlineHistory,
  liveRegionSubmittedClearLineCount,
  liveRegionClearLineCount,
  renderPanelDuringReadlineQuestion,
  resetReadlineBuffer,
  shouldCloseForBackendEvent,
  shouldRenderDetailsAt,
  shouldRefreshLiveRegionAfterReadlineKey,
  shouldSendCommandForDetails,
  createSigintShutdownHandler
} from "../src/interactive-controls.js";

test("Ctrl+T requests local details panel without producing a Java command", () => {
  const action = controlKeyActionForKey({ ctrl: true, name: "t" });

  assert.deepEqual(action, { type: "render_details" });
  assert.equal("command" in action, false);
});

test("non Ctrl+T keypresses do not trigger details panel", () => {
  assert.deepEqual(controlKeyActionForKey({ ctrl: true, name: "c" }), { type: "none" });
  assert.deepEqual(controlKeyActionForKey({ name: "t" }), { type: "none" });
});

test("running backend guard blocks duplicate user_submit but keeps responses flowing", () => {
  const running = { sessionLabel: "ui-session", backendStatus: "Running" } as const;

  assert.equal(shouldSendCommandForDetails({ type: "user_submit", text: "next" }, running), false);
  assert.equal(shouldSendCommandForDetails({
    type: "permission_response",
    requestId: "req-1",
    choiceKey: "allow_once",
    feedback: null
  }, running), true);
  assert.equal(shouldSendCommandForDetails({ type: "ask_user_answer", toolUseId: "ask-1", text: "README.md" }, running), true);
  assert.equal(shouldSendCommandForDetails({ type: "shutdown" }, running), true);
});

test("fatal backend errors close the interactive session", () => {
  assert.equal(shouldCloseForBackendEvent({
    type: "error",
    message: "Session belongs to a different cwd",
    recoverable: false
  }), true);
  assert.equal(shouldCloseForBackendEvent({
    type: "error",
    message: "Backend is busy",
    recoverable: true
  }), false);
  assert.equal(shouldCloseForBackendEvent({
    type: "ready",
    sessionId: "session-1",
    cwd: "E:\\work",
    model: "mock"
  }), false);
});

test("Ctrl+T duplicate guard suppresses immediate repeated keypress events", () => {
  assert.equal(shouldRenderDetailsAt(1_000, 0), true);
  assert.equal(shouldRenderDetailsAt(1_050, 1_000), false);
  assert.equal(shouldRenderDetailsAt(1_250, 1_000), true);
});

test("Ctrl+T panel render clears and restores active readline prompt text", () => {
  const rendered = renderPanelDuringReadlineQuestion({
    panel: "session details\nbackend: Ready",
    prompt: "minicode-ui> ",
    line: "half typed"
  });

  assert.equal(
    rendered,
    "\r\x1b[2Ksession details\nbackend: Ready\nminicode-ui> half typed"
  );
});

test("SIGINT handler sends one shutdown and closes readline once", () => {
  const commands: unknown[] = [];
  let closes = 0;
  let suppressions = 0;
  let terminations = 0;
  const handler = createSigintShutdownHandler({
    sendCommand: (command) => commands.push(command),
    closeReadline: () => { closes++; },
    suppressBackendOutput: () => { suppressions++; },
    terminateBackend: () => { terminations++; }
  });

  handler();
  handler();

  assert.deepEqual(commands, [{ type: "shutdown" }]);
  assert.equal(closes, 1);
  assert.equal(suppressions, 1);
  assert.equal(terminations, 1);
});

test("resetReadlineBuffer clears stale submitted input before live region redraw", () => {
  const rl = { line: "mock permission flow", cursor: 20 };

  resetReadlineBuffer(rl);

  assert.equal(rl.line, "");
  assert.equal(rl.cursor, 0);
});

test("disableReadlineHistory clears existing history and caps future history", () => {
  const rl = { history: ["mock permission flow"], historySize: 30 };

  disableReadlineHistory(rl);

  assert.deepEqual(rl.history, []);
  assert.equal(rl.historySize, 0);
});

test("liveRegionClearLineCount clears newly expanded live area", () => {
  assert.equal(liveRegionClearLineCount(2, 8), 8);
  assert.equal(liveRegionClearLineCount(8, 2), 8);
  assert.equal(liveRegionClearLineCount(0, 3), 3);
});

test("liveRegionSubmittedClearLineCount includes readline-wrapped submitted input", () => {
  assert.equal(liveRegionSubmittedClearLineCount(2, 6), 6);
  assert.equal(liveRegionSubmittedClearLineCount(6, 2), 6);
});

test("readline editing keys request live region refresh", () => {
  assert.equal(shouldRefreshLiveRegionAfterReadlineKey({ name: "a" }), true);
  assert.equal(shouldRefreshLiveRegionAfterReadlineKey({ name: "backspace" }), true);
  assert.equal(shouldRefreshLiveRegionAfterReadlineKey({ name: "delete" }), true);
  assert.equal(shouldRefreshLiveRegionAfterReadlineKey({ name: "left" }), true);
  assert.equal(shouldRefreshLiveRegionAfterReadlineKey({ name: "return" }), false);
  assert.equal(shouldRefreshLiveRegionAfterReadlineKey({ ctrl: true, name: "c" }), false);
});
