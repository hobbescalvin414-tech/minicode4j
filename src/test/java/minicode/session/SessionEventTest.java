package minicode.session;

import minicode.core.message.UserMessage;
import minicode.session.model.RenameDraft;
import minicode.session.model.SessionEvent;
import minicode.session.model.SessionEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionEventTest {
    @Test
    void messageEventRequiresMessagePayload() {
        SessionEvent event = SessionEvent.message(
                "event-1",
                Instant.EPOCH,
                "session-1",
                "E:/work",
                Optional.empty(),
                Optional.empty(),
                new UserMessage("hello")
        );

        assertEquals(SessionEventType.MESSAGE, event.type());
        assertTrue(event.message().isPresent());
        assertTrue(event.meta().isEmpty());
    }

    @Test
    void renameMetaEventDoesNotForgeMessage() {
        SessionEvent event = SessionEvent.meta(
                "event-2",
                Instant.EPOCH,
                "session-1",
                "E:/work",
                Optional.empty(),
                Optional.empty(),
                new RenameDraft("new title")
        );

        assertEquals(SessionEventType.RENAME, event.type());
        assertTrue(event.message().isEmpty());
        assertInstanceOf(RenameDraft.class, event.meta().orElseThrow());
    }

    @Test
    void factoryRejectsMetaPayloadOnMessageEvent() {
        assertThrows(IllegalArgumentException.class, () -> SessionEvent.create(
                SessionEventType.MESSAGE,
                "event-3",
                Instant.EPOCH,
                "session-1",
                "E:/work",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new RenameDraft("bad"))
        ));
    }
}
