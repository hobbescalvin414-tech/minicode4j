package minicode.core.event;

import java.util.Objects;

@FunctionalInterface
public interface AgentEventSink {
    void onEvent(AgentEvent event);

    static AgentEventSink noOp() {
        return event -> Objects.requireNonNull(event, "event");
    }
}
