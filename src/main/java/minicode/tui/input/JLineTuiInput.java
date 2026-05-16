package minicode.tui.input;

import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.Objects;

public final class JLineTuiInput implements TuiInput {
    private final NonBlockingReader reader;

    public JLineTuiInput(Terminal terminal) {
        Terminal actualTerminal = Objects.requireNonNull(terminal, "terminal");
        actualTerminal.enterRawMode();
        Attributes attributes = actualTerminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        actualTerminal.setAttributes(attributes);
        this.reader = actualTerminal.reader();
    }

    @Override
    public TuiInputEvent readEvent() throws IOException {
        int value = reader.read();
        if (value < 0) {
            return TuiInputEvent.eof();
        }
        return switch (value) {
            case '\r', '\n' -> TuiInputEvent.submit();
            case '\b', 127 -> TuiInputEvent.backspace();
            case 27 -> readEscapeEvent();
            default -> TuiInputEvent.character((char) value);
        };
    }

    private TuiInputEvent readEscapeEvent() throws IOException {
        int next = reader.read();
        if (next < 0) {
            return TuiInputEvent.eof();
        }
        if (next == 'O') {
            return readApplicationCursorEvent();
        }
        if (next != '[') {
            return TuiInputEvent.character((char) next);
        }
        int code = reader.read();
        if (code < 0) {
            return TuiInputEvent.eof();
        }
        return switch (code) {
            case 'A' -> TuiInputEvent.scrollUp();
            case 'B' -> TuiInputEvent.scrollDown();
            case 'C' -> TuiInputEvent.cursorRight();
            case 'D' -> TuiInputEvent.cursorLeft();
            case '5' -> consumeTilde(TuiInputEvent.pageUp());
            case '6' -> consumeTilde(TuiInputEvent.pageDown());
            case '<' -> readSgrMouseEvent();
            default -> TuiInputEvent.character((char) code);
        };
    }

    private TuiInputEvent readApplicationCursorEvent() throws IOException {
        int code = reader.read();
        if (code < 0) {
            return TuiInputEvent.eof();
        }
        return switch (code) {
            case 'A' -> TuiInputEvent.scrollUp();
            case 'B' -> TuiInputEvent.scrollDown();
            case 'C' -> TuiInputEvent.cursorRight();
            case 'D' -> TuiInputEvent.cursorLeft();
            default -> TuiInputEvent.character((char) code);
        };
    }

    private TuiInputEvent consumeTilde(TuiInputEvent event) throws IOException {
        int terminator = reader.read();
        return terminator == '~' ? event : TuiInputEvent.character((char) terminator);
    }

    private TuiInputEvent readSgrMouseEvent() throws IOException {
        StringBuilder firstNumber = new StringBuilder();
        while (true) {
            int value = reader.read();
            if (value < 0) {
                return TuiInputEvent.eof();
            }
            if (value == ';') {
                break;
            }
            firstNumber.append((char) value);
        }
        while (true) {
            int value = reader.read();
            if (value < 0) {
                return TuiInputEvent.eof();
            }
            if (value == 'M' || value == 'm') {
                break;
            }
        }
        return switch (parseInt(firstNumber.toString())) {
            case 64 -> TuiInputEvent.scrollUp();
            case 65 -> TuiInputEvent.scrollDown();
            default -> TuiInputEvent.eof();
        };
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
