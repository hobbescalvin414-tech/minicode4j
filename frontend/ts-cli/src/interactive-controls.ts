import type { UiCommand } from "./protocol.js";
import type { SessionDetailsState } from "./session-details.js";

export type ControlKey = {
  ctrl?: boolean;
  name?: string;
};

export type ControlKeyAction =
  | { type: "render_details" }
  | { type: "none" };

export function controlKeyActionForKey(key: ControlKey | undefined): ControlKeyAction {
  if (key?.ctrl === true && key.name?.toLowerCase() === "t") {
    return { type: "render_details" };
  }
  return { type: "none" };
}

export function shouldSendCommandForDetails(command: UiCommand, state: Pick<SessionDetailsState, "backendStatus">): boolean {
  return !(command.type === "user_submit" && state.backendStatus === "Running");
}

export function renderPanelDuringReadlineQuestion(options: {
  panel: string;
  prompt: string;
  line: string;
}): string {
  return `\r\x1b[2K${options.panel}\n${options.prompt}${options.line}`;
}

export function shouldRenderDetailsAt(nowMs: number, lastRenderedAtMs: number): boolean {
  return nowMs - lastRenderedAtMs >= 150;
}

export function resetReadlineBuffer(rl: unknown): void {
  const writable = rl as { line?: unknown; cursor?: unknown };
  if ("line" in writable) {
    writable.line = "";
  }
  if ("cursor" in writable) {
    writable.cursor = 0;
  }
}

export function disableReadlineHistory(rl: unknown): void {
  const writable = rl as { history?: unknown; historySize?: unknown };
  if ("history" in writable) {
    writable.history = [];
  }
  if ("historySize" in writable) {
    writable.historySize = 0;
  }
}

export function liveRegionClearLineCount(previousLineCount: number, nextLineCount: number): number {
  return Math.max(0, previousLineCount, nextLineCount);
}

export function liveRegionSubmittedClearLineCount(previousLineCount: number, submittedLineCount: number): number {
  return Math.max(0, previousLineCount, submittedLineCount);
}

export function shouldRefreshLiveRegionAfterReadlineKey(key: ControlKey | undefined): boolean {
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

export function createSigintShutdownHandler(options: {
  sendCommand: (command: UiCommand) => void;
  closeReadline: () => void;
  suppressBackendOutput?: () => void;
  terminateBackend?: () => void;
}): () => void {
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
