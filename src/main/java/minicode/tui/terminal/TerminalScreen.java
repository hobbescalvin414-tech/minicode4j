package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;

public interface TerminalScreen extends AutoCloseable {
    TerminalSize size();

    void redraw(RenderFrame frame);

    @Override
    default void close() {
    }
}
