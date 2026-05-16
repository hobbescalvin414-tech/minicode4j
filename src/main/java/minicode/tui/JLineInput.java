package minicode.tui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.util.Objects;

public final class JLineInput implements LineInput {
    private final LineReader reader;

    public JLineInput(LineReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public String readLine() throws IOException {
        try {
            return reader.readLine();
        } catch (EndOfFileException exception) {
            return null;
        } catch (UserInterruptException exception) {
            throw new IOException("Interrupted by user", exception);
        }
    }
}
