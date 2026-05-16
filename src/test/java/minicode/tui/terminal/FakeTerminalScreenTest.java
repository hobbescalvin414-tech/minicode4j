package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeTerminalScreenTest {
    @Test
    void redrawStoresLatestFrameSnapshot() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(10, 3));
        RenderFrame first = new RenderFrame(10, 3, List.of("one       ", "two       ", "three     "));
        RenderFrame second = new RenderFrame(10, 3, List.of("alpha     ", "beta      ", "gamma     "));

        screen.redraw(first);
        screen.redraw(second);

        assertEquals(new TerminalSize(10, 3), screen.size());
        assertEquals(second, screen.latestFrame().orElseThrow());
        assertEquals(List.of(first, second), screen.frames());
    }

    @Test
    void latestTextReflectsOnlyLatestRedrawFrame() {
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(12, 2));
        RenderFrame first = new RenderFrame(12, 2, List.of("old status  ", "old input   "));
        RenderFrame second = new RenderFrame(12, 2, List.of("new status  ", "new input   "));

        screen.redraw(first);
        screen.redraw(second);

        assertEquals("new status  " + System.lineSeparator() + "new input   ", screen.latestText());
    }
}
