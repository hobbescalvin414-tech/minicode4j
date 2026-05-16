package minicode.core.turn;

public enum AgentTurnStopReason {
    FINAL,
    AWAIT_USER,
    MAX_STEPS,
    MODEL_ERROR,
    CANCELLED,
    EMPTY_RESPONSE_FALLBACK
}
