package minicode.tui.render;

import java.util.Objects;

public record InputState(Mode mode, String text, int cursor) {
    public InputState {
        mode = Objects.requireNonNull(mode, "mode");
        text = Objects.requireNonNull(text, "text");
        if (cursor < 0 || cursor > text.length()) {
            throw new IllegalArgumentException("cursor must be inside input text");
        }
    }

    public static InputState empty() {
        return new InputState(Mode.NORMAL, "", 0);
    }

    public static InputState of(String text, int cursor) {
        return new InputState(Mode.NORMAL, text, cursor);
    }

    public static InputState of(Mode mode, String text, int cursor) {
        return new InputState(mode, text, cursor);
    }

    public InputState withMode(Mode mode) {
        return new InputState(mode, text, cursor);
    }

    public enum Mode {
        NORMAL,
        BUSY,
        AWAITING_ASK_USER,
        PENDING_PERMISSION,
        PERMISSION_FEEDBACK
    }
}
