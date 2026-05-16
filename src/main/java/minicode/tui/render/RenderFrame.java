package minicode.tui.render;

import java.util.List;
import java.util.Objects;

public record RenderFrame(int width, int height, List<String> lines, int cursorRow, int cursorColumn) {
    public RenderFrame(int width, int height, List<String> lines) {
        this(width, height, lines, 0, 0);
    }

    public RenderFrame {
        if (width < 1) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height < 1) {
            throw new IllegalArgumentException("height must be positive");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.size() != height) {
            throw new IllegalArgumentException("line count must match height");
        }
        for (String line : lines) {
            if (DisplayText.width(Objects.requireNonNull(line, "line")) != width) {
                throw new IllegalArgumentException("each line display width must match width");
            }
        }
        if (cursorRow < 0 || cursorRow > height) {
            throw new IllegalArgumentException("cursorRow must be 0 or inside frame height");
        }
        if (cursorColumn < 0 || cursorColumn > width) {
            throw new IllegalArgumentException("cursorColumn must be 0 or inside frame width");
        }
        if ((cursorRow == 0) != (cursorColumn == 0)) {
            throw new IllegalArgumentException("cursor row and column must both be 0 or both be positive");
        }
    }

    public String text() {
        return String.join(System.lineSeparator(), lines);
    }
}
