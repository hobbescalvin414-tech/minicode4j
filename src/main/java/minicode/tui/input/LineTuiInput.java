package minicode.tui.input;

import minicode.tui.LineInput;

import java.io.IOException;
import java.util.Objects;

public final class LineTuiInput implements TuiInput {
    private final LineInput input;

    public LineTuiInput(LineInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public TuiInputEvent readEvent() throws IOException {
        String line = input.readLine();
        return line == null ? TuiInputEvent.eof() : TuiInputEvent.submitLine(line);
    }
}
