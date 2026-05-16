package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FakeTerminalScreen implements TerminalScreen {
    private final TerminalSize size;
    private final ArrayList<RenderFrame> frames = new ArrayList<>();

    public FakeTerminalScreen(TerminalSize size) {
        this.size = Objects.requireNonNull(size, "size");
    }

    @Override
    public TerminalSize size() {
        return size;
    }

    @Override
    public void redraw(RenderFrame frame) {
        frames.add(Objects.requireNonNull(frame, "frame"));
    }

    public Optional<RenderFrame> latestFrame() {
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.getLast());
    }

    public List<RenderFrame> frames() {
        return List.copyOf(frames);
    }

    public String latestText() {
        return latestFrame().map(RenderFrame::text).orElse("");
    }
}
