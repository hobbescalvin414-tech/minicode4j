package minicode.session.model;

import java.util.Objects;

public record RenameDraft(String title) implements MetaSessionEventDraft {
    public RenameDraft {
        if (Objects.requireNonNull(title, "title").isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
    }

    @Override
    public SessionEventType eventType() {
        return SessionEventType.RENAME;
    }
}
