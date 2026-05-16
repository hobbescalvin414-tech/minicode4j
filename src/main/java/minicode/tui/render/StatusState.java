package minicode.tui.render;

import java.util.Objects;
import java.util.Optional;

public record StatusState(Optional<String> text) {
    public StatusState {
        text = Objects.requireNonNull(text, "text");
    }

    public static StatusState empty() {
        return new StatusState(Optional.empty());
    }

    public static StatusState thinking() {
        return of("Thinking...");
    }

    public static StatusState of(String text) {
        if (Objects.requireNonNull(text, "text").isBlank()) {
            return empty();
        }
        return new StatusState(Optional.of(text));
    }
}
