package minicode.session;

import minicode.context.compact.CompactMetadata;
import minicode.context.compact.CompactTrigger;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.UserMessage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.model.ForkDraft;
import minicode.session.model.RenameDraft;
import minicode.session.model.SessionEventType;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.service.SessionService;
import minicode.session.store.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void listSessionsReturnsOnlyCurrentCwdMetadata() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store);
        store.append(new SessionEventFactory("left", "E:/left").message(new UserMessage("left title")));
        store.append(new SessionEventFactory("right", "E:/right").message(new UserMessage("right title")));

        var sessions = service.list("E:/left");

        assertEquals(1, sessions.size());
        assertEquals("left", sessions.getFirst().sessionId());
        assertEquals(Optional.of("left title"), sessions.getFirst().title());
        assertEquals("E:/left", sessions.getFirst().cwd());
    }

    @Test
    void renameWritesMetaEventAndMetadataUsesNewTitle() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store);
        store.append(new SessionEventFactory("session-1", "E:/work").message(new UserMessage("old title")));

        service.rename("E:/work", "session-1", "new title");

        var events = store.readAll("session-1", "E:/work");
        assertEquals(SessionEventType.RENAME, events.getLast().type());
        assertTrue(events.getLast().message().isEmpty());
        assertEquals(new RenameDraft("new title"), events.getLast().meta().orElseThrow());
        assertEquals(Optional.of("new title"), store.readMetadata("session-1", "E:/work").orElseThrow().title());
        assertEquals(Optional.of(events.get(events.size() - 2).uuid()), events.getLast().parentUuid());
    }

    @Test
    void renameRejectsBlankTitle() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store);
        store.append(new SessionEventFactory("session-1", "E:/work").message(new UserMessage("old title")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.rename("E:/work", "session-1", "   "));

        assertEquals("Session title must not be blank.", exception.getMessage());
    }

    @Test
    void rejectsInvalidSessionId() {
        SessionService service = new SessionService(new SessionStore(tempDir));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.resumeMessages("E:/work", "../bad"));

        assertEquals("Invalid session id: ../bad", exception.getMessage());
    }

    @Test
    void resumeMessagesUseLatestCompactBoundaryReplay() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store,
                new SessionEventFactory("session-1", "E:/work"));
        ContextSummaryMessage summary = new ContextSummaryMessage("summary", 2, Instant.EPOCH);
        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("old"))),
                new PersistenceAction.AppendCompactBoundaryAction(summary,
                        new CompactMetadata(CompactTrigger.AUTO, 100, 25, 2, Instant.EPOCH)),
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("new")))
        )));

        assertEquals(List.of(summary, new UserMessage("new")),
                service.resumeMessages("E:/work", "session-1"));
    }

    @Test
    void forkCreatesNewSessionWithLineageMetadataAndReplayMessages() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store, () -> "forked-session");
        store.append(new SessionEventFactory("source", "E:/work").message(new UserMessage("source title")));

        String forkedId = service.fork("E:/work", "source");

        assertEquals("forked-session", forkedId);
        var forkedEvents = store.readAll("forked-session", "E:/work");
        assertEquals(SessionEventType.FORK, forkedEvents.getFirst().type());
        ForkDraft forkDraft = assertInstanceOf(ForkDraft.class, forkedEvents.getFirst().meta().orElseThrow());
        assertEquals("source", forkDraft.metadata().sourceSessionId());
        assertEquals("forked-session", forkDraft.metadata().newSessionId());
        assertEquals(Optional.of(store.readAll("source", "E:/work").getLast().uuid()),
                forkDraft.metadata().sourceEventId());
        assertEquals(new UserMessage("source title"), forkedEvents.get(1).message().orElseThrow());
        assertTrue(service.list("E:/work").stream().anyMatch(meta -> meta.sessionId().equals("forked-session")));
    }

    @Test
    void forkUsesLatestCompactBoundaryReplayFromSourceSession() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store, () -> "forked-session");
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store,
                new SessionEventFactory("source", "E:/work"));
        ContextSummaryMessage summary = new ContextSummaryMessage("summary", 2, Instant.EPOCH);
        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("old"))),
                new PersistenceAction.AppendCompactBoundaryAction(summary,
                        new CompactMetadata(CompactTrigger.AUTO, 100, 25, 2, Instant.EPOCH)),
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("new")))
        )));

        service.fork("E:/work", "source");

        var forkedMessages = store.readAll("forked-session", "E:/work").stream()
                .flatMap(event -> event.message().stream())
                .toList();
        assertEquals(List.of(summary, new UserMessage("new")), forkedMessages);
    }

    @Test
    void forkRetriesWhenGeneratedSessionIdAlreadyExists() {
        SessionStore store = new SessionStore(tempDir);
        store.append(new SessionEventFactory("source", "E:/work").message(new UserMessage("source title")));
        store.append(new SessionEventFactory("existing", "E:/work").message(new UserMessage("existing title")));
        AtomicInteger calls = new AtomicInteger();
        SessionService service = new SessionService(store,
                () -> calls.getAndIncrement() == 0 ? "existing" : "forked-session");

        String forkedId = service.fork("E:/work", "source");

        assertEquals("forked-session", forkedId);
        assertEquals(List.of(new UserMessage("existing title")),
                store.readAll("existing", "E:/work").stream()
                        .map(event -> event.message().orElseThrow())
                        .toList());
    }

    @Test
    void forkFailsClearlyWhenGeneratedSessionIdsKeepColliding() {
        SessionStore store = new SessionStore(tempDir);
        store.append(new SessionEventFactory("source", "E:/work").message(new UserMessage("source title")));
        store.append(new SessionEventFactory("existing", "E:/work").message(new UserMessage("existing title")));
        SessionService service = new SessionService(store, () -> "existing");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.fork("E:/work", "source"));

        assertEquals("Unable to allocate unique fork session id after 5 attempts.", exception.getMessage());
    }

    @Test
    void missingSessionAndCwdMismatchHaveClearErrors() {
        SessionStore store = new SessionStore(tempDir);
        SessionService service = new SessionService(store);
        store.append(new SessionEventFactory("session-1", "E:/other").message(new UserMessage("title")));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.resumeMessages("E:/work", "missing"));
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> service.resumeMessages("E:/work", "session-1"));

        assertEquals("Session not found: missing", missing.getMessage());
        assertEquals("Session session-1 belongs to a different cwd: E:/other", mismatch.getMessage());
    }
}
