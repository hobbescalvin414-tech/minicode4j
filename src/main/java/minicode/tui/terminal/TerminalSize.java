package minicode.tui.terminal;

public record TerminalSize(int columns, int rows) {
    public TerminalSize {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be positive");
        }
        if (rows < 1) {
            throw new IllegalArgumentException("rows must be positive");
        }
    }
}
