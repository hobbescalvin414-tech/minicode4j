package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.Objects;

public final class JLineTerminalScreen implements TerminalScreen {
    private static final String ENTER_ALTERNATE_SCREEN = "\u001B[?1049h";
    private static final String EXIT_ALTERNATE_SCREEN = "\u001B[?1049l";
    private static final String ENABLE_ALTERNATE_SCROLL = "\u001B[?1007h";
    private static final String DISABLE_ALTERNATE_SCROLL = "\u001B[?1007l";
    private static final String CURSOR_HOME = "\u001B[H";
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    private final Terminal terminal;
    private final PrintWriter writer;
    private boolean closed;

    public JLineTerminalScreen(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.writer = terminal.writer();
        writer.print(ENTER_ALTERNATE_SCREEN);
        writer.print(ENABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.print(CLEAR_SCREEN);
        writer.print(CURSOR_HOME);
        writer.flush();
    }

    @Override
    public TerminalSize size() {
        return new TerminalSize(Math.max(1, terminal.getWidth()), Math.max(1, terminal.getHeight()));
    }

    @Override
    public void redraw(RenderFrame frame) {
        Objects.requireNonNull(frame, "frame");
        writer.print(SHOW_CURSOR);
        writer.print(CURSOR_HOME);
        writer.print(CLEAR_SCREEN);
        writer.print(CURSOR_HOME);
        writer.print(String.join("\r\n", frame.lines()));
        if (frame.cursorRow() > 0 && frame.cursorColumn() > 0) {
            writer.print("\u001B[" + frame.cursorRow() + ";" + frame.cursorColumn() + "H");
        }
        writer.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.print(DISABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.print(EXIT_ALTERNATE_SCREEN);
        writer.flush();
    }
}
