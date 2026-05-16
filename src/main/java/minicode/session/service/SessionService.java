package minicode.session.service;

import minicode.core.message.ChatMessage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.model.ForkDraft;
import minicode.session.model.ForkMetadata;
import minicode.session.model.RenameDraft;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.store.SessionMetadata;
import minicode.session.store.SessionStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class SessionService {
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_FORK_ID_ATTEMPTS = 5;

    private final SessionStore store;
    private final Supplier<String> sessionIdSupplier;
    private final Clock clock;

    public SessionService(SessionStore store) {
        this(store, () -> UUID.randomUUID().toString());
    }

    public SessionService(SessionStore store, Supplier<String> sessionIdSupplier) {
        this(store, sessionIdSupplier, Clock.systemUTC());
    }

    public SessionService(SessionStore store, Supplier<String> sessionIdSupplier, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<SessionMetadata> list(String cwd) {
        return store.listSessionsByCwd(requireText(cwd, "cwd"));
    }

    public void requireResumable(String cwd, String sessionId) {
        requireExistingInCwd(cwd, sessionId);
    }

    public List<ChatMessage> resumeMessages(String cwd, String sessionId) {
        requireExistingInCwd(cwd, sessionId);
        List<ChatMessage> messages = store.loadMessagesSinceLatestCompactBoundary(sessionId, cwd);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Session has no resumable messages: " + sessionId);
        }
        return messages;
    }

    public void rename(String cwd, String sessionId, String title) {
        String actualTitle = requireTitle(title);
        requireExistingInCwd(cwd, sessionId);
        runnerFor(cwd, sessionId).apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendSessionEventAction(new RenameDraft(actualTitle))
        )));
    }

    public String fork(String cwd, String sourceSessionId) {
        requireExistingInCwd(cwd, sourceSessionId);
        List<ChatMessage> messages = resumeMessages(cwd, sourceSessionId);
        String newSessionId = allocateForkSessionId(cwd);
        Optional<String> sourceEventId = store.latestEventUuid(sourceSessionId, cwd);
        SessionPersistenceRunner runner = runnerFor(cwd, newSessionId);
        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendSessionEventAction(new ForkDraft(new ForkMetadata(
                        sourceSessionId,
                        sourceEventId,
                        newSessionId,
                        cwd,
                        Instant.now(clock)
                ))),
                new PersistenceAction.AppendMessagesAction(messages)
        )));
        return newSessionId;
    }

    private SessionPersistenceRunner runnerFor(String cwd, String sessionId) {
        return new SessionPersistenceRunner(store, new SessionEventFactory(
                sessionId,
                cwd,
                clock,
                () -> UUID.randomUUID().toString(),
                store.latestEventUuid(sessionId, cwd)
        ));
    }

    private String allocateForkSessionId(String cwd) {
        for (int attempt = 0; attempt < MAX_FORK_ID_ATTEMPTS; attempt++) {
            String candidate = requireSessionId(sessionIdSupplier.get());
            if (store.readMetadata(candidate, cwd).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate unique fork session id after "
                + MAX_FORK_ID_ATTEMPTS + " attempts.");
    }

    private void requireExistingInCwd(String cwd, String sessionId) {
        String actualCwd = requireText(cwd, "cwd");
        String actualSessionId = requireSessionId(sessionId);
        if (store.readMetadata(actualSessionId, actualCwd).isPresent()) {
            return;
        }
        List<String> otherCwds = store.findCwdsForSessionId(actualSessionId).stream()
                .filter(candidate -> !candidate.equals(actualCwd))
                .toList();
        if (!otherCwds.isEmpty()) {
            throw new IllegalArgumentException("Session " + actualSessionId
                    + " belongs to a different cwd: " + otherCwds.getFirst());
        }
        throw new IllegalArgumentException("Session not found: " + actualSessionId);
    }

    private static String requireTitle(String value) {
        if (Objects.requireNonNull(value, "title").isBlank()) {
            throw new IllegalArgumentException("Session title must not be blank.");
        }
        return value.trim();
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSessionId(String sessionId) {
        String value = requireText(sessionId, "sessionId");
        if (!SESSION_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid session id: " + value);
        }
        return value;
    }
}
