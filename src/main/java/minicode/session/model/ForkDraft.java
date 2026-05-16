package minicode.session.model;

import java.util.Objects;

public record ForkDraft(ForkMetadata metadata) implements MetaSessionEventDraft {
    public ForkDraft {
        metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public SessionEventType eventType() {
        return SessionEventType.FORK;
    }
}
