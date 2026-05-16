package minicode.session.factory;

import minicode.context.compact.CompactMetadata;
import minicode.core.message.ChatMessage;
import minicode.session.model.MetaSessionEventDraft;
import minicode.session.model.SessionEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class SessionEventFactory {
    private final String sessionId;
    private final String cwd;
    private final Clock clock;
    private final Supplier<String> uuidSupplier;
    private Optional<String> lastEventUuid = Optional.empty();

    public SessionEventFactory(String sessionId, String cwd) {
        this(sessionId, cwd, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    public SessionEventFactory(String sessionId, String cwd, Clock clock, Supplier<String> uuidSupplier) {
        this(sessionId, cwd, clock, uuidSupplier, Optional.empty());
    }

    public SessionEventFactory(String sessionId, String cwd, Clock clock, Supplier<String> uuidSupplier,
                               Optional<String> lastEventUuid) {
        this.sessionId = requireText(sessionId, "sessionId");
        this.cwd = requireText(cwd, "cwd");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.lastEventUuid = Objects.requireNonNull(lastEventUuid, "lastEventUuid");
    }

    public String sessionId() {
        return sessionId;
    }

    public String cwd() {
        return cwd;
    }

    public SessionEvent message(ChatMessage message) {
        SessionEvent event = SessionEvent.message(nextUuid(), now(), sessionId, cwd,
                lastEventUuid, lastEventUuid, message);
        remember(event);
        return event;
    }

    public SessionEvent meta(MetaSessionEventDraft draft) {
        SessionEvent event = SessionEvent.meta(nextUuid(), now(), sessionId, cwd,
                lastEventUuid, lastEventUuid, draft);
        remember(event);
        return event;
    }

    public SessionEvent compactBoundary() {
        SessionEvent event = SessionEvent.compactBoundary(nextUuid(), now(), sessionId, cwd,
                lastEventUuid, lastEventUuid);
        remember(event);
        return event;
    }

    public SessionEvent compactBoundary(CompactMetadata metadata) {
        SessionEvent event = SessionEvent.compactBoundary(nextUuid(), now(), sessionId, cwd,
                lastEventUuid, lastEventUuid, metadata);
        remember(event);
        return event;
    }

    private void remember(SessionEvent event) {
        lastEventUuid = Optional.of(event.uuid());
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private String nextUuid() {
        return requireText(uuidSupplier.get(), "uuid");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
