package minicode.tui.input;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes.LocalFlag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JLineTuiInputTest {
    @Test
    void parsesPageUpAndPageDownEscapeSequences() throws Exception {
        JLineTuiInput input = input("\u001B[5~\u001B[6~");

        assertEquals(TuiInputEvent.pageUp(), input.readEvent());
        assertEquals(TuiInputEvent.pageDown(), input.readEvent());
    }

    @Test
    void parsesArrowKeysAsTranscriptScrollEvents() throws Exception {
        JLineTuiInput input = input("\u001B[A\u001B[B");

        assertEquals(TuiInputEvent.scrollUp(), input.readEvent());
        assertEquals(TuiInputEvent.scrollDown(), input.readEvent());
    }

    @Test
    void parsesApplicationCursorKeysWithoutLeakingEscapeBytesIntoInput() throws Exception {
        JLineTuiInput input = input("\u001BOA\u001BOB\u001BOD\u001BOC");

        assertEquals(TuiInputEvent.scrollUp(), input.readEvent());
        assertEquals(TuiInputEvent.scrollDown(), input.readEvent());
        assertEquals(TuiInputEvent.cursorLeft(), input.readEvent());
        assertEquals(TuiInputEvent.cursorRight(), input.readEvent());
    }

    @Test
    void parsesCsiLeftAndRightAsCursorMovement() throws Exception {
        JLineTuiInput input = input("\u001B[D\u001B[C");

        assertEquals(TuiInputEvent.cursorLeft(), input.readEvent());
        assertEquals(TuiInputEvent.cursorRight(), input.readEvent());
    }

    @Test
    void parsesSgrMouseWheelEscapeSequences() throws Exception {
        JLineTuiInput input = input("\u001B[<64;10;5M\u001B[<65;10;5M");

        assertEquals(TuiInputEvent.scrollUp(), input.readEvent());
        assertEquals(TuiInputEvent.scrollDown(), input.readEvent());
    }

    @Test
    void parsesCharactersBackspaceEnterAndEof() throws Exception {
        JLineTuiInput input = input("a\b\r");

        assertEquals(TuiInputEvent.character('a'), input.readEvent());
        assertEquals(TuiInputEvent.backspace(), input.readEvent());
        assertEquals(TuiInputEvent.submit(), input.readEvent());
        assertEquals(TuiInputEvent.eof(), input.readEvent());
    }

    @Test
    void constructorEntersRawModeSoMouseAndKeysBypassLineEditor() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .type("xterm")
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .encoding(StandardCharsets.UTF_8)
                .build();

        new JLineTuiInput(terminal);

        assertFalse(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
        assertFalse(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
    }

    private static JLineTuiInput input(String text) throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .type("xterm")
                .streams(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream())
                .encoding(StandardCharsets.UTF_8)
                .build();
        return new JLineTuiInput(terminal);
    }
}
