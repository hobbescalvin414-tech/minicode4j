package minicode.tui.input;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

public final class ScriptedTuiInput implements TuiInput {
    private final ArrayDeque<TuiInputEvent> events;

    public ScriptedTuiInput(List<TuiInputEvent> events) {
        this.events = new ArrayDeque<>(Objects.requireNonNull(events, "events"));
    }

    @Override
    public TuiInputEvent readEvent() {
        return events.isEmpty() ? TuiInputEvent.eof() : events.removeFirst();
    }
}
