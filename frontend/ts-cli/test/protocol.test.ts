import test from "node:test";
import assert from "node:assert/strict";
import { parseJsonlEvents, toCommandLine } from "../src/protocol.js";

test("parses valid UI JSONL events and ignores blank lines", () => {
  const events = parseJsonlEvents(`
{"type":"ready","sessionId":"mock-session","cwd":"E:\\\\project","model":"mock"}

{"type":"turn_stop","reason":"FINAL"}
`);

  assert.equal(events.length, 2);
  assert.equal(events[0].type, "ready");
  assert.equal(events[1].type, "turn_stop");
});

test("rejects invalid JSONL with line number", () => {
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"ready\",\"sessionId\":\"s\",\"cwd\":\"E:\\\\project\",\"model\":\"mock\"}\nnot-json\n"),
    /Invalid UI JSONL at line 2/
  );
});

test("rejects UI JSONL values that are not event objects", () => {
  assert.throws(
    () => parseJsonlEvents("[]\n"),
    /Invalid UI event at line 1: expected JSON object/
  );
});

test("rejects events with missing or invalid required fields", () => {
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"ready\",\"sessionId\":\"s\"}\n"),
    /Invalid UI event at line 1: ready.cwd must be a string/
  );
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"tool_finished\",\"toolUseId\":\"tool-1\",\"toolName\":\"read_file\",\"status\":\"ok\",\"summary\":\"path=README.md\",\"preview\":\"body\",\"truncated\":true,\"hiddenLines\":\"7\",\"storageRef\":null}\n"),
    /Invalid UI event at line 1: tool_finished.hiddenLines must be a number/
  );
});

test("parses tool_finished diff preview metadata", () => {
  const [event] = parseJsonlEvents(
    "{\"type\":\"tool_finished\",\"toolUseId\":\"tool-1\",\"toolName\":\"edit_file\",\"status\":\"ok\",\"summary\":\"path=README.md\",\"preview\":\"updated\",\"truncated\":false,\"hiddenLines\":0,\"storageRef\":null,\"diffPreview\":{\"title\":\"README.md\",\"lines\":[\"-old\",\"+new\"],\"truncated\":true,\"hiddenLines\":4}}\n"
  );

  assert.equal(event.type, "tool_finished");
  assert.deepEqual(event.diffPreview, {
    title: "README.md",
    lines: ["-old", "+new"],
    truncated: true,
    hiddenLines: 4
  });
});

test("rejects unsupported enum-like protocol values", () => {
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"tool_finished\",\"toolUseId\":\"tool-1\",\"toolName\":\"read_file\",\"status\":\"maybe\",\"summary\":\"path=README.md\",\"preview\":\"body\",\"truncated\":false,\"hiddenLines\":0,\"storageRef\":null}\n"),
    /tool_finished.status must be one of/
  );
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"turn_stop\",\"reason\":\"DONE\"}\n"),
    /turn_stop.reason must be one of/
  );
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"permission_audit\",\"requestId\":\"req-1\",\"decision\":\"maybe\",\"choiceKey\":\"allow_once\",\"summary\":\"allowed\"}\n"),
    /permission_audit.decision must be one of/
  );
  assert.throws(
    () => parseJsonlEvents("{\"type\":\"context_stats\",\"badge\":\"context\",\"totalTokens\":1,\"effectiveInput\":10,\"source\":\"raw-provider\",\"warning\":\"normal\"}\n"),
    /context_stats.source must be one of/
  );
});

test("serializes frontend commands as JSONL", () => {
  assert.equal(
    toCommandLine({ type: "user_submit", text: "hello" }),
    "{\"type\":\"user_submit\",\"text\":\"hello\"}"
  );
  assert.equal(
    toCommandLine({ type: "shutdown" }),
    "{\"type\":\"shutdown\"}"
  );
});

test("serializes TS-UI-2 response commands as JSONL", () => {
  assert.equal(
    toCommandLine({ type: "permission_response", requestId: "req-1", choiceKey: "allow_once", feedback: null }),
    "{\"type\":\"permission_response\",\"requestId\":\"req-1\",\"choiceKey\":\"allow_once\",\"feedback\":null}"
  );
  assert.equal(
    toCommandLine({ type: "ask_user_answer", toolUseId: "tool-2", text: "Use README.md" }),
    "{\"type\":\"ask_user_answer\",\"toolUseId\":\"tool-2\",\"text\":\"Use README.md\"}"
  );
});
