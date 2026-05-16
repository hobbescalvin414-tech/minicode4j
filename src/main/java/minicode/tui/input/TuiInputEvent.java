package minicode.tui.input;

import java.util.Objects;
import java.util.Optional;

public record TuiInputEvent(Kind kind, Optional<Character> character, Optional<String> text) {
    public TuiInputEvent {
        kind = Objects.requireNonNull(kind, "kind");
        character = Objects.requireNonNull(character, "character");
        text = Objects.requireNonNull(text, "text");
    }

    public static TuiInputEvent character(char value) {
        return new TuiInputEvent(Kind.CHARACTER, Optional.of(value), Optional.empty());
    }

    public static TuiInputEvent backspace() {
        return simple(Kind.BACKSPACE);
    }

    public static TuiInputEvent submit() {
        return simple(Kind.SUBMIT);
    }

    public static TuiInputEvent submitLine(String text) {
        return new TuiInputEvent(Kind.SUBMIT, Optional.empty(), Optional.of(Objects.requireNonNull(text, "text")));
    }

    public static TuiInputEvent pageUp() {
        return simple(Kind.PAGE_UP);
    }

    public static TuiInputEvent pageDown() {
        return simple(Kind.PAGE_DOWN);
    }

    public static TuiInputEvent scrollUp() {
        return simple(Kind.SCROLL_UP);
    }

    public static TuiInputEvent scrollDown() {
        return simple(Kind.SCROLL_DOWN);
    }

    public static TuiInputEvent cursorLeft() {
        return simple(Kind.CURSOR_LEFT);
    }

    public static TuiInputEvent cursorRight() {
        return simple(Kind.CURSOR_RIGHT);
    }

    public static TuiInputEvent eof() {
        return simple(Kind.EOF);
    }

    private static TuiInputEvent simple(Kind kind) {
        return new TuiInputEvent(kind, Optional.empty(), Optional.empty());
    }

    public enum Kind {
        CHARACTER,
        BACKSPACE,
        SUBMIT,
        PAGE_UP,
        PAGE_DOWN,
        SCROLL_UP,
        SCROLL_DOWN,
        CURSOR_LEFT,
        CURSOR_RIGHT,
        EOF
    }
}
