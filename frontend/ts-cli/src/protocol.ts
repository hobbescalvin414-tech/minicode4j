export type UiEvent =
  | { type: "ready"; sessionId: string; cwd: string; model: string }
  | { type: "history_item"; id: string; kind: string; text: string }
  | { type: "assistant_message"; id: string; text: string }
  | { type: "assistant_progress"; id: string; text: string }
  | { type: "tool_started"; toolUseId: string; toolName: string; summary: string }
  | {
      type: "tool_finished";
      toolUseId: string;
      toolName: string;
      status: string;
      summary: string;
      preview: string;
      truncated: boolean;
      hiddenLines: number;
      storageRef: string | null;
      diffPreview?: {
        title?: string;
        lines: string[];
        truncated?: boolean;
        hiddenLines?: number;
      };
    }
  | {
      type: "permission_request";
      requestId: string;
      title: string;
      body: string;
      facts: string[];
      choices: Array<{ key: string; label: string }>;
    }
  | { type: "permission_audit"; requestId: string; decision: string; choiceKey: string; summary: string }
  | { type: "await_user"; toolUseId: string; question: string }
  | {
      type: "context_stats";
      badge: string;
      totalTokens: number;
      effectiveInput: number;
      source: string;
      warning: string;
    }
  | { type: "status"; text: string; busy: boolean }
  | { type: "turn_stop"; reason: string; message?: string }
  | { type: "error"; message: string; recoverable: boolean };

export type UiCommand =
  | { type: "init"; cwd: string; home?: string; sessionId?: string | null; resumeSessionId?: string | null; maxSteps?: number }
  | { type: "user_submit"; text: string }
  | { type: "ask_user_answer"; toolUseId: string; text: string }
  | { type: "permission_response"; requestId: string; choiceKey: string; feedback?: string | null }
  | { type: "shutdown" };

export function parseJsonlEvents(text: string): UiEvent[] {
  const events: UiEvent[] = [];
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index].trim();
    if (line.length === 0) {
      continue;
    }
    try {
      events.push(validateUiEvent(JSON.parse(line), index + 1));
    } catch (error) {
      if ((error as Error).message.startsWith("Invalid UI event at line ")) {
        throw error;
      }
      throw new Error(`Invalid UI JSONL at line ${index + 1}: ${(error as Error).message}`);
    }
  }
  return events;
}

export function toCommandLine(command: UiCommand): string {
  return JSON.stringify(command);
}

function validateUiEvent(value: unknown, line: number): UiEvent {
  if (!isRecord(value)) {
    throw invalid(line, "expected JSON object");
  }
  const type = expectString(value, "type", line);
  switch (type) {
    case "ready":
      expectString(value, "sessionId", line, "ready.sessionId");
      expectString(value, "cwd", line, "ready.cwd");
      expectString(value, "model", line, "ready.model");
      return value as UiEvent;
    case "history_item":
      expectString(value, "id", line);
      expectString(value, "kind", line);
      expectString(value, "text", line);
      return value as UiEvent;
    case "assistant_message":
    case "assistant_progress":
      expectString(value, "id", line);
      expectString(value, "text", line);
      return value as UiEvent;
    case "tool_started":
      expectString(value, "toolUseId", line);
      expectString(value, "toolName", line);
      expectString(value, "summary", line);
      return value as UiEvent;
    case "tool_finished":
      expectString(value, "toolUseId", line);
      expectString(value, "toolName", line);
      expectOneOf(value, "status", line, ["ok", "error", "failed"], "tool_finished.status");
      expectString(value, "summary", line);
      expectString(value, "preview", line);
      expectBoolean(value, "truncated", line);
      expectNumber(value, "hiddenLines", line, "tool_finished.hiddenLines");
      expectNullableString(value, "storageRef", line, "tool_finished.storageRef");
      if ("diffPreview" in value && value.diffPreview !== undefined) {
        validateDiffPreview(value.diffPreview, line);
      }
      return value as UiEvent;
    case "permission_request":
      expectString(value, "requestId", line);
      expectString(value, "title", line);
      expectString(value, "body", line);
      expectStringArray(value, "facts", line);
      expectChoiceArray(value, "choices", line);
      return value as UiEvent;
    case "permission_audit":
      expectString(value, "requestId", line);
      expectOneOf(value, "decision", line, ["allowed", "denied"], "permission_audit.decision");
      expectString(value, "choiceKey", line);
      expectString(value, "summary", line);
      return value as UiEvent;
    case "await_user":
      expectString(value, "toolUseId", line);
      expectString(value, "question", line);
      return value as UiEvent;
    case "context_stats":
      expectString(value, "badge", line);
      expectNumber(value, "totalTokens", line);
      expectNumber(value, "effectiveInput", line);
      expectOneOf(value, "source", line, ["provider", "provider+estimate", "estimate"], "context_stats.source");
      expectOneOf(value, "warning", line, ["ok", "normal", "warning", "critical", "blocked"], "context_stats.warning");
      return value as UiEvent;
    case "status":
      expectString(value, "text", line);
      expectBoolean(value, "busy", line);
      return value as UiEvent;
    case "turn_stop":
      expectOneOf(value, "reason", line,
        ["FINAL", "AWAIT_USER", "MAX_STEPS", "MODEL_ERROR", "CANCELLED", "EMPTY_RESPONSE_FALLBACK"],
        "turn_stop.reason");
      if ("message" in value && value.message !== undefined) {
        expectString(value, "message", line);
      }
      return value as UiEvent;
    case "error":
      expectString(value, "message", line);
      expectBoolean(value, "recoverable", line);
      return value as UiEvent;
    default:
      throw invalid(line, `unsupported event type: ${type}`);
  }
}

function validateDiffPreview(value: unknown, line: number): void {
  if (!isRecord(value)) {
    throw invalid(line, "tool_finished.diffPreview must be an object");
  }
  if ("title" in value && value.title !== undefined) {
    expectString(value, "title", line, "tool_finished.diffPreview.title");
  }
  expectStringArray(value, "lines", line, "tool_finished.diffPreview.lines");
  if ("truncated" in value && value.truncated !== undefined) {
    expectBoolean(value, "truncated", line, "tool_finished.diffPreview.truncated");
  }
  if ("hiddenLines" in value && value.hiddenLines !== undefined) {
    expectNumber(value, "hiddenLines", line, "tool_finished.diffPreview.hiddenLines");
  }
}

function expectChoiceArray(value: Record<string, unknown>, field: string, line: number): void {
  const choices = value[field];
  if (!Array.isArray(choices)) {
    throw invalid(line, `${field} must be an array`);
  }
  choices.forEach((choice, index) => {
    if (!isRecord(choice)) {
      throw invalid(line, `${field}[${index}] must be an object`);
    }
    expectString(choice, "key", line, `${field}[${index}].key`);
    expectString(choice, "label", line, `${field}[${index}].label`);
  });
}

function expectStringArray(value: Record<string, unknown>, field: string, line: number, label = field): void {
  const array = value[field];
  if (!Array.isArray(array) || array.some((item) => typeof item !== "string")) {
    throw invalid(line, `${label} must be a string array`);
  }
}

function expectString(value: Record<string, unknown>, field: string, line: number, label = field): string {
  if (typeof value[field] !== "string") {
    throw invalid(line, `${label} must be a string`);
  }
  return value[field];
}

function expectOneOf(
  value: Record<string, unknown>,
  field: string,
  line: number,
  allowed: string[],
  label = field
): void {
  const actual = expectString(value, field, line, label);
  if (!allowed.includes(actual)) {
    throw invalid(line, `${label} must be one of: ${allowed.join(", ")}`);
  }
}

function expectNullableString(value: Record<string, unknown>, field: string, line: number, label = field): void {
  if (typeof value[field] !== "string" && value[field] !== null) {
    throw invalid(line, `${label} must be a string or null`);
  }
}

function expectBoolean(value: Record<string, unknown>, field: string, line: number, label = field): void {
  if (typeof value[field] !== "boolean") {
    throw invalid(line, `${label} must be a boolean`);
  }
}

function expectNumber(value: Record<string, unknown>, field: string, line: number, label = field): void {
  if (typeof value[field] !== "number" || !Number.isFinite(value[field])) {
    throw invalid(line, `${label} must be a number`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalid(line: number, message: string): Error {
  return new Error(`Invalid UI event at line ${line}: ${message}`);
}
