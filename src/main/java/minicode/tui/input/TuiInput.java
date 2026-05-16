package minicode.tui.input;

import java.io.IOException;

public interface TuiInput {
    TuiInputEvent readEvent() throws IOException;
}
