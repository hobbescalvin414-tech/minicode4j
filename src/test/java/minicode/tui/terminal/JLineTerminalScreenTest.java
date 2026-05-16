package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JLineTerminalScreenTest {
    @Test
    void screenUsesAlternateScreenAndClearsForRedraws() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .type("xterm")
                .streams(new ByteArrayInputStream(new byte[0]), output)
                .encoding(StandardCharsets.UTF_8)
                .build();

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.redraw(new RenderFrame(8, 3, List.of(
                "one     ",
                "two     ",
                ">       "
        )));
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?1049h") || text.contains("\u001B[?47h"), text);
        assertTrue(text.contains("\u001B[H") || text.contains("\u001B[1;1H"), text);
        assertTrue(text.contains("\u001B[2J") || text.contains("\u001B[J"), text);
        assertTrue(text.contains("\u001B[?1049l") || text.contains("\u001B[?47l"), text);
    }

    @Test
    void screenKeepsMouseSelectionAvailableAndEnablesAlternateScroll() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .type("xterm")
                .streams(new ByteArrayInputStream(new byte[0]), output)
                .encoding(StandardCharsets.UTF_8)
                .build();

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?1007h"), text);
        assertTrue(text.contains("\u001B[?1007l"), text);
        assertTrue(!text.contains("\u001B[?1000h"), text);
        assertTrue(!text.contains("\u001B[?1006h"), text);
    }

    @Test
    void redrawShowsAndPositionsRealTerminalCursor() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .type("xterm")
                .streams(new ByteArrayInputStream(new byte[0]), output)
                .encoding(StandardCharsets.UTF_8)
                .build();

        JLineTerminalScreen screen = new JLineTerminalScreen(terminal);
        screen.redraw(new RenderFrame(20, 3, List.of(
                "MiniCode            ",
                "Ready               ",
                "mini-code>          "
        ), 3, 12));
        screen.close();

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[?25h"), text);
        assertTrue(text.contains("\u001B[3;12H"), text);
    }
}
