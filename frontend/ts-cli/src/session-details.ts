import type { UiEvent } from "./protocol.js";

export type BackendStatus = "Ready" | "Running" | "Awaiting permission" | "Awaiting answer";

export type SessionDetailsState = {
  sessionLabel: string;
  cwd?: string;
  model?: string;
  backendStatus: BackendStatus;
  lastStatusText?: string;
  recentContext?: {
    badge: string;
    percent: number;
    totalTokens: number;
    effectiveInput: number;
    remaining: number;
    source: string;
    warning: string;
  };
  recentTool?: {
    toolUseId: string;
    toolName: string;
    status: "running" | "ok" | "error" | "failed";
    summary: string;
    truncated: boolean;
    hiddenLines: number;
    storageRef: string | null;
  };
  recentPermission?: {
    requestId: string;
    status: "pending" | "approved" | "denied";
    choiceKey?: string;
    summary?: string;
  };
  recentAskUser?: {
    toolUseId: string;
    status: "pending" | "answered";
    question?: string;
  };
};

export function initialSessionDetailsState(): SessionDetailsState {
  return {
    sessionLabel: "mock-runTurn",
    backendStatus: "Ready"
  };
}

export function reduceSessionDetailsState(state: SessionDetailsState, event: UiEvent): SessionDetailsState {
  switch (event.type) {
    case "ready":
      return {
        ...state,
        sessionLabel: event.sessionId.length > 0 ? event.sessionId : "mock-runTurn",
        cwd: event.cwd,
        model: event.model,
        backendStatus: "Ready",
        lastStatusText: "Ready"
      };
    case "status":
      return {
        ...state,
        backendStatus: statusFromBusyFlag(state, event.busy),
        lastStatusText: event.text
      };
    case "context_stats":
      return {
        ...state,
        recentContext: {
          badge: event.badge,
          percent: percent(event.totalTokens, event.effectiveInput),
          totalTokens: event.totalTokens,
          effectiveInput: event.effectiveInput,
          remaining: Math.max(0, event.effectiveInput - event.totalTokens),
          source: event.source,
          warning: event.warning
        }
      };
    case "tool_started":
      return {
        ...state,
        backendStatus: "Running",
        recentTool: {
          toolUseId: event.toolUseId,
          toolName: event.toolName,
          status: "running",
          summary: event.summary,
          truncated: false,
          hiddenLines: 0,
          storageRef: null
        }
      };
    case "tool_finished":
      return {
        ...state,
        recentTool: {
          toolUseId: event.toolUseId,
          toolName: event.toolName,
          status: toolStatus(event.status),
          summary: event.summary,
          truncated: event.truncated,
          hiddenLines: event.hiddenLines,
          storageRef: event.storageRef
        }
      };
    case "permission_request":
      return {
        ...state,
        backendStatus: "Awaiting permission",
        recentPermission: {
          requestId: event.requestId,
          status: "pending",
          summary: event.title
        }
      };
    case "permission_audit":
      return {
        ...state,
        backendStatus: state.recentAskUser?.status === "pending" ? "Awaiting answer" : "Running",
        recentPermission: {
          requestId: event.requestId,
          status: event.decision === "allowed" ? "approved" : "denied",
          choiceKey: event.choiceKey,
          summary: event.summary
        }
      };
    case "await_user":
      return {
        ...state,
        backendStatus: "Awaiting answer",
        recentAskUser: {
          toolUseId: event.toolUseId,
          status: "pending",
          question: event.question
        }
      };
    case "turn_stop":
      if (event.reason === "AWAIT_USER") {
        return {
          ...state,
          backendStatus: "Awaiting answer"
        };
      }
      return {
        ...state,
        backendStatus: "Ready",
        recentAskUser: state.recentAskUser?.status === "pending"
          ? { ...state.recentAskUser, status: "answered" }
          : state.recentAskUser
      };
    case "error":
      return event.recoverable
        ? state
        : {
            ...state,
            backendStatus: "Ready"
          };
    default:
      return state;
  }
}

function statusFromBusyFlag(state: SessionDetailsState, busy: boolean): BackendStatus {
  if (!busy) {
    return "Ready";
  }
  if (state.recentPermission?.status === "pending") {
    return "Awaiting permission";
  }
  if (state.recentAskUser?.status === "pending") {
    return "Awaiting answer";
  }
  return "Running";
}

function percent(totalTokens: number, effectiveInput: number): number {
  if (effectiveInput <= 0) {
    return 0;
  }
  return Math.round((totalTokens / effectiveInput) * 100);
}

function toolStatus(status: string): "ok" | "error" | "failed" {
  return status === "failed" ? "failed" : status === "error" ? "error" : "ok";
}
