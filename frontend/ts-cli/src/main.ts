import fs from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { clearLine, cursorTo, emitKeypressEvents, moveCursor } from "node:readline";
import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output, stderr } from "node:process";
import {
  defaultDistRoot,
  defaultRepoRoot,
  startJavaMockRunTurnBackend,
  startJavaRealBackend,
  type JavaBackendHandle
} from "./java-backend.js";
import {
  controlKeyActionForKey,
  createSigintShutdownHandler,
  disableReadlineHistory,
  liveRegionClearLineCount,
  liveRegionSubmittedClearLineCount,
  resetReadlineBuffer,
  shouldCloseForBackendEvent,
  shouldRenderDetailsAt,
  shouldRefreshLiveRegionAfterReadlineKey,
  shouldSendCommandForDetails
} from "./interactive-controls.js";
import {
  commandForLatestInputLine,
  initialInputState,
  permissionCancelCommand,
  reduceInputStateForEvent,
  reduceInputStateForEmptySubmit,
  reduceInputStateForPermissionSelection,
  reduceInputStateForSubmittedCommand,
  type UiInputState
} from "./input-state.js";
import { parseJsonlEvents, toCommandLine, type UiCommand } from "./protocol.js";
import { StreamingRenderer, renderDetailsPanel, renderEvents, renderLiveRegionLayout, renderSubmittedCommandLine } from "./renderer.js";
import { initialSessionDetailsState, reduceSessionDetailsState, type SessionDetailsState } from "./session-details.js";

type Args = {
  mockFile?: string;
  cwd: string;
  home?: string;
  maxSteps?: number;
  real: boolean;
  resumeSessionId?: string;
};

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  if (args.mockFile != null) {
    const text = await fs.readFile(args.mockFile, "utf8");
    output.write(renderEvents(parseJsonlEvents(text)) + "\n");
    return;
  }

  const repoRoot = process.env.MINICODE4J_REPO_ROOT ?? defaultRepoRoot(import.meta.url);
  const distRoot = process.env.MINICODE4J_DIST_ROOT ?? defaultDistRoot(import.meta.url);
  const backend = args.real
    ? startJavaRealBackend({ repoRoot, distRoot, workspaceCwd: args.cwd, maxSteps: args.maxSteps })
    : startJavaMockRunTurnBackend({ repoRoot, workspaceCwd: args.cwd, maxSteps: args.maxSteps });
  let inputState = initialInputState();
  let detailsState = initialSessionDetailsState();
  let stderrText = "";
  let suppressBackendOutput = false;
  backend.process.stdout.setEncoding("utf8");
  backend.process.stderr.setEncoding("utf8");
  backend.process.stderr.on("data", (chunk: string) => {
    stderrText += chunk;
  });
  await readInteractiveCommands({
    backend,
    initialCommand: {
      type: "init",
      cwd: args.cwd,
      home: args.home,
      sessionId: null,
      resumeSessionId: args.resumeSessionId ?? null,
      maxSteps: args.maxSteps
    },
    getInputState: () => inputState,
    setInputState: (next) => { inputState = next; },
    getDetailsState: () => detailsState,
    setDetailsState: (next) => { detailsState = next; },
    shouldRenderBackendOutput: () => !suppressBackendOutput,
    suppressBackendOutput: () => {
      suppressBackendOutput = true;
    }
  });
  const exitCode = await new Promise<number | null>((resolve) => backend.process.on("close", resolve));
  if (stderrText.trim().length > 0) {
    stderr.write(stderrText);
  }
  if (exitCode !== 0) {
    throw new Error(`Java ${args.real ? "real" : "mock runTurn"} backend exited with code ${exitCode}`);
  }
}

function parseArgs(argv: string[]): Args {
  const copy = [...argv];
  const mockFile = takeOption(copy, "--mock-file");
  const real = takeFlag(copy, "--real");
  const cwd = takeOption(copy, "--cwd") ?? process.cwd();
  const home = takeOption(copy, "--home") ?? process.env.MINICODE4J_UI_HOME;
  const maxStepsText = takeOption(copy, "--max-steps");
  const resumeSessionId = takeOption(copy, "--resume");
  if (copy.length > 0) {
    throw new Error(`Unknown argument: ${copy[0]}`);
  }
  return {
    mockFile: mockFile ?? undefined,
    cwd: path.resolve(cwd),
    home,
    maxSteps: maxStepsText == null ? undefined : parseMaxSteps(maxStepsText),
    real,
    resumeSessionId: resumeSessionId ?? undefined
  };
}

function takeFlag(args: string[], name: string): boolean {
  const index = args.indexOf(name);
  if (index < 0) {
    return false;
  }
  args.splice(index, 1);
  return true;
}

function takeOption(args: string[], name: string): string | null {
  const index = args.indexOf(name);
  if (index < 0) {
    return null;
  }
  if (index + 1 >= args.length) {
    throw new Error(`Missing value for ${name}`);
  }
  const value = args[index + 1];
  args.splice(index, 2);
  return value;
}

function parseMaxSteps(value: string): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) {
    throw new Error("--max-steps must be between 1 and 100");
  }
  return parsed;
}

function attachBackendRenderer(
  backend: JavaBackendHandle,
  onEvent: (event: ReturnType<typeof parseJsonlEvents>[number]) => void,
  shouldRender: () => boolean = () => true,
  writeRendered: (text: string) => void = (text) => {
    if (text.length > 0) {
      output.write(text + "\n");
    }
  }
): void {
  let buffer = "";
  const renderer = new StreamingRenderer();
  backend.process.stdout.on("data", (chunk: string) => {
    buffer += chunk;
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (line.trim().length === 0) {
        continue;
      }
      const [event] = parseJsonlEvents(`${line}\n`);
      onEvent(event);
      if (!shouldRender()) {
        continue;
      }
      const rendered = renderer.render(event);
      writeRendered(rendered);
    }
  });
}

async function readInteractiveCommands(
  options: {
    backend: JavaBackendHandle;
    initialCommand: UiCommand;
    getInputState: () => UiInputState;
    setInputState: (state: UiInputState) => void;
    getDetailsState: () => SessionDetailsState;
    setDetailsState: (state: SessionDetailsState) => void;
    shouldRenderBackendOutput?: () => boolean;
    suppressBackendOutput?: () => void;
  }
): Promise<void> {
  const backend = options.backend;
  const getInputState = options.getInputState;
  const getDetailsState = options.getDetailsState;
  if (!input.isTTY) {
    sendCommand(backend, { type: "shutdown" });
    return;
  }
  const rl = createInterface({ input, output, historySize: 0 });
  disableReadlineHistory(rl);
  emitKeypressEvents(input, rl);
  const live = new LiveRegionController(rl, getInputState, getDetailsState);
  let interrupted = false;
  attachBackendRenderer(backend, (event) => {
    options.setDetailsState(reduceSessionDetailsState(getDetailsState(), event));
    options.setInputState(reduceInputStateForEvent(getInputState(), event));
    if (shouldCloseForBackendEvent(event)) {
      interrupted = true;
      terminateBackendProcess(backend);
      rl.close();
    }
  }, () => options.shouldRenderBackendOutput?.() ?? true, (text) => {
    live.writeTranscript(text);
  });
  let lastDetailsRenderedAtMs = 0;
  const onSigint = createSigintShutdownHandler({
    sendCommand: (command) => sendCommand(backend, command),
    suppressBackendOutput: options.suppressBackendOutput,
    terminateBackend: () => terminateBackendProcess(backend),
    closeReadline: () => {
      interrupted = true;
      rl.close();
    }
  });
  const onKeypress = (_sequence: string, key: { ctrl?: boolean; name?: string }) => {
    const action = controlKeyActionForKey(key);
    if (action.type === "render_details") {
      const now = Date.now();
      if (!shouldRenderDetailsAt(now, lastDetailsRenderedAtMs)) {
        return;
      }
      lastDetailsRenderedAtMs = now;
      live.writePanel(renderDetailsPanel(getDetailsState(), getInputState()));
      return;
    }
    if (getInputState().mode === "permission" && (key.name === "down" || key.name === "right")) {
      options.setInputState(reduceInputStateForPermissionSelection(getInputState(), 1));
      live.refresh();
      return;
    }
    if (getInputState().mode === "permission" && (key.name === "up" || key.name === "left")) {
      options.setInputState(reduceInputStateForPermissionSelection(getInputState(), -1));
      live.refresh();
      return;
    }
    if (getInputState().mode === "permission" && (key.name === "escape" || key.name === "esc")) {
      const command = permissionCancelCommand(getInputState());
      if (command == null) {
        return;
      }
      live.clearAfterEnter();
      sendCommand(backend, command);
      options.setInputState(reduceInputStateForSubmittedCommand(getInputState(), command));
      live.refresh();
      return;
    }
    if (shouldRefreshLiveRegionAfterReadlineKey(key)) {
      setImmediate(() => live.refresh());
    }
  };
  input.on("keypress", onKeypress);
  rl.on("SIGINT", onSigint);
  process.once("SIGINT", onSigint);
  try {
    live.refresh();
    sendCommand(backend, options.initialCommand);
    while (true) {
      if (interrupted) {
        return;
      }
      let line: string;
      try {
        line = await rl.question("");
      } catch (error) {
        if (interrupted) {
          return;
        }
        throw error;
      }
      resetReadlineBuffer(rl);
      const state = getInputState();
      const command = commandForLatestInputLine(getInputState, line);
      if (command == null) {
        live.clearAfterEnter(line, line.length);
        const nextState = reduceInputStateForEmptySubmit(state);
        if (nextState !== state) {
          options.setInputState(nextState);
        } else if (state.mode === "permission") {
          output.write(`${mutedText("Enter approve, deny, deny <feedback>, or a listed choice key.")}\n`);
        } else if (state.mode === "permission_feedback") {
          output.write(`${mutedText("Enter feedback for MiniCode, or press esc to cancel.")}\n`);
        }
        live.refresh();
        continue;
      }
      if (!shouldSendCommandForDetails(command, getDetailsState())) {
        live.clearAfterEnter(line, line.length);
        output.write(`${mutedText("Backend is still running; wait for Ready before submitting another task.")}\n`);
        live.refresh();
        continue;
      }
      live.clearAfterEnter(line, line.length);
      const submittedLine = renderSubmittedCommandLine(command, line);
      if (submittedLine != null) {
        output.write(`${submittedLine}\n`);
      }
      sendCommand(backend, command);
      options.setInputState(reduceInputStateForSubmittedCommand(getInputState(), command));
      if (command.type === "shutdown") {
        return;
      }
      live.refresh();
    }
  } finally {
    live.clearLiveRegion();
    input.off("keypress", onKeypress);
    rl.off("SIGINT", onSigint);
    process.off("SIGINT", onSigint);
    rl.close();
  }
}

class LiveRegionController {
  private renderedRowCount = 0;
  private renderedRowsBelowCursor = 0;

  constructor(
    private readonly rl: unknown,
    private readonly getInputState: () => UiInputState,
    private readonly getDetailsState: () => SessionDetailsState
  ) {}

  writeTranscript(text: string): void {
    this.clear();
    if (text.length > 0) {
      output.write(text + "\n");
    }
    this.refresh();
  }

  writePanel(panel: string): void {
    this.clear();
    output.write(panel + "\n");
    this.refresh();
  }

  refresh(): void {
    this.clear();
    const line = currentReadlineLine(this.rl);
    const cursor = currentReadlineCursor(this.rl);
    const layout = renderLiveRegionLayout({
      detailsState: this.getDetailsState(),
      inputState: this.getInputState(),
      line,
      cursor,
      columns: output.columns
    });
    if (layout.text.length === 0) {
      this.renderedRowCount = 0;
      this.renderedRowsBelowCursor = 0;
      return;
    }
    this.clearAdditionalRows(layout.terminalRowCount);
    this.writeClearedLines(layout.text);
    if (layout.rowsBelowCursor > 0) {
      moveCursor(output, 0, -layout.rowsBelowCursor);
    }
    this.renderedRowCount = layout.terminalRowCount;
    this.renderedRowsBelowCursor = layout.rowsBelowCursor;
    cursorTo(output, layout.promptColumn);
  }

  clearAfterEnter(submittedLine = currentReadlineLine(this.rl), submittedCursor = currentReadlineCursor(this.rl)): void {
    if (this.renderedRowCount <= 0) {
      return;
    }
    const submittedLayout = renderLiveRegionLayout({
      detailsState: this.getDetailsState(),
      inputState: this.getInputState(),
      line: submittedLine,
      cursor: submittedCursor,
      columns: output.columns
    });
    this.renderedRowCount = liveRegionSubmittedClearLineCount(
      this.renderedRowCount,
      submittedLayout.terminalRowCount
    );
    this.renderedRowsBelowCursor = submittedLayout.rowsBelowCursor;
    output.write("\x1b[1A");
    this.clear();
  }

  private clear(): void {
    if (this.renderedRowCount <= 0) {
      return;
    }
    if (this.renderedRowsBelowCursor > 0) {
      moveCursor(output, 0, this.renderedRowsBelowCursor);
    }
    cursorTo(output, 0);
    clearLine(output, 0);
    for (let index = 1; index < this.renderedRowCount; index++) {
      output.write("\x1b[1A");
      clearLine(output, 0);
    }
    this.renderedRowCount = 0;
    this.renderedRowsBelowCursor = 0;
  }

  clearLiveRegion(): void {
    this.clear();
  }

  private clearAdditionalRows(nextLineCount: number): void {
    const extraRows = liveRegionClearLineCount(this.renderedRowCount, nextLineCount) - this.renderedRowCount;
    for (let index = 0; index < extraRows; index++) {
      clearLine(output, 0);
      output.write("\n");
    }
    for (let index = 0; index < extraRows; index++) {
      output.write("\x1b[1A");
    }
  }

  private writeClearedLines(text: string): void {
    const lines = text.split("\n");
    lines.forEach((line, index) => {
      clearLine(output, 0);
      output.write(line);
      if (index < lines.length - 1) {
        output.write("\n");
      }
    });
  }
}

function currentReadlineLine(rl: unknown): string {
  const line = (rl as { line?: unknown }).line;
  return typeof line === "string" ? line : "";
}

function currentReadlineCursor(rl: unknown): number {
  const cursor = (rl as { cursor?: unknown }).cursor;
  return typeof cursor === "number" ? cursor : currentReadlineLine(rl).length;
}

function renderDetailsDuringQuestion(rl: unknown, panel: string, prompt: string): void {
  const line = currentReadlineLine(rl);
  const cursor = currentReadlineCursor(rl);
  cursorTo(output, 0);
  clearLine(output, 0);
  output.write(`${panel}\n${prompt}${line}`);
  cursorTo(output, prompt.length + cursor);
}

function mutedText(text: string): string {
  return `\x1b[90m${text}\x1b[0m`;
}

function sendCommand(backend: JavaBackendHandle, command: UiCommand): void {
  if (!backend.process.stdin.writable) {
    return;
  }
  backend.process.stdin.write(toCommandLine(command) + "\n");
}

function terminateBackendProcess(backend: JavaBackendHandle): void {
  try {
    backend.process.stdin.end();
  } catch {
    // Best-effort interrupt cleanup.
  }
  const pid = backend.process.pid;
  if (process.platform === "win32" && pid != null) {
    spawn("taskkill", ["/pid", String(pid), "/T", "/F"], {
      stdio: "ignore",
      windowsHide: true
    }).on("error", () => {
      // Fall back to child.kill below.
    });
  }
  try {
    backend.process.kill();
  } catch {
    // Process may already be gone.
  }
}

main().catch((error: unknown) => {
  stderr.write(`ts-ui error: ${(error as Error).message}\n`);
  process.exitCode = 1;
});
