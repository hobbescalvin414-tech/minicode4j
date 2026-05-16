package minicode.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Objects;

public final class BufferedLineInput implements LineInput {
    private final BufferedReader reader;

    public BufferedLineInput(BufferedReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }
}
