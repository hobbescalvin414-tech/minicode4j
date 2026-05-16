package minicode.core.turn;

public enum CancellationPhase {
    BEFORE_TURN,
    MODEL_REQUEST,
    PERMISSION_PROMPT,
    TOOL_EXECUTION,
    AFTER_TURN
}
