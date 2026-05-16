package minicode.session;

import minicode.context.compact.CompactMetadata;
import minicode.context.compact.CompactTrigger;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;
import minicode.session.factory.SessionEventFactory;
import minicode.session.model.RenameDraft;
import minicode.session.model.SessionEvent;
import minicode.session.model.SessionEventType;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;

import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.store.SessionStore;
import minicode.session.transcript.SessionTranscriptProjector;
import minicode.session.transcript.TranscriptEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void appendAndReadMessageEvent() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        SessionEvent event = factory.message(new UserMessage("hello"));

        store.append(event);

        List<SessionEvent> events = store.readAll("session-1", "E:/work");
        assertEquals(1, events.size());
        assertEquals(SessionEventType.MESSAGE, events.getFirst().type());
        assertEquals(new UserMessage("hello"), events.getFirst().message().orElseThrow());
    }

    @Test
    void persistedEventTypeUsesLowercaseSemanticStringInsteadOfJavaEnumName() {
        SessionStore store = new SessionStore(tempDir);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store, new SessionEventFactory("session-1", "E:/work"));
        ContextSummaryMessage summary = new ContextSummaryMessage("summary", 2, Instant.EPOCH);

        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(
                        new UserMessage("hello"),
                        new AssistantMessage("answer"),
                        new AssistantProgressMessage("working"),
                        new ToolResultMessage("tool-1", "read_file", "output", false),
                        summary
                )),
                new PersistenceAction.AppendCompactBoundaryAction(
                        summary,
                        new CompactMetadata(CompactTrigger.AUTO, 100, 25, 2, Instant.EPOCH)
                ),
                new PersistenceAction.AppendSessionEventAction(new RenameDraft("renamed"))
        )));

        List<String> lines = jsonLines();

        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"user\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"assistant\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"progress\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"tool_result\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"summary\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"compact_boundary\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"rename\"")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\"type\":\"MESSAGE\"")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\"type\":\"COMPACT_BOUNDARY\"")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\"type\":\"RENAME\"")));
    }

    @Test
    void readsLegacyUppercaseJavaEnumTypeForExistingJsonl() throws Exception {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        store.append(factory.message(new UserMessage("legacy")));
        Path jsonl = jsonlFiles().getFirst();
        String legacyLine = java.nio.file.Files.readString(jsonl).replace("\"type\":\"user\"", "\"type\":\"MESSAGE\"");
        java.nio.file.Files.writeString(jsonl, legacyLine);

        List<SessionEvent> events = store.readAll("session-1", "E:/work");

        assertEquals(1, events.size());
        assertEquals(SessionEventType.MESSAGE, events.getFirst().type());
        assertEquals(new UserMessage("legacy"), events.getFirst().message().orElseThrow());
    }

    @Test
    void parentUuidChainIsPreservedAcrossAppends() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        SessionEvent first = factory.message(new UserMessage("one"));
        SessionEvent second = factory.message(new AssistantMessage("two"));

        store.append(first);
        store.append(second);

        List<SessionEvent> events = store.readAll("session-1", "E:/work");
        assertTrue(events.get(0).parentUuid().isEmpty());
        assertEquals(Optional.of(events.get(0).uuid()), events.get(1).parentUuid());
        assertEquals(events.get(1).parentUuid(), events.get(1).logicalParentUuid());
    }

    @Test
    void resumedFactoryContinuesParentChainFromLastEvent() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory firstFactory = new SessionEventFactory("session-1", "E:/work");
        SessionEvent first = firstFactory.message(new UserMessage("one"));
        store.append(first);

        SessionEventFactory resumedFactory = new SessionEventFactory(
                "session-1",
                "E:/work",
                java.time.Clock.systemUTC(),
                () -> "resumed-event",
                Optional.of(first.uuid())
        );
        store.append(resumedFactory.message(new AssistantMessage("two")));

        List<SessionEvent> events = store.readAll("session-1", "E:/work");
        assertEquals(Optional.of(first.uuid()), events.get(1).parentUuid());
    }

    @Test
    void compactBoundaryWritesMetaBoundaryAndSummaryMessage() {
        SessionStore store = new SessionStore(tempDir);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store, new SessionEventFactory("session-1", "E:/work"));
        ContextSummaryMessage summary = new ContextSummaryMessage("summary", 3, Instant.EPOCH);
        CompactMetadata metadata = new CompactMetadata(CompactTrigger.AUTO, 100, 25, 3, Instant.EPOCH);

        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendCompactBoundaryAction(summary, metadata)
        )));

        List<SessionEvent> events = store.readAll("session-1", "E:/work");
        assertEquals(2, events.size());
        assertEquals(SessionEventType.COMPACT_BOUNDARY, events.get(0).type());
        assertTrue(events.get(0).message().isEmpty());
        assertEquals(Optional.of(metadata), events.get(0).compactMetadata());
        assertEquals(SessionEventType.MESSAGE, events.get(1).type());
        assertEquals(summary, events.get(1).message().orElseThrow());
        assertEquals(Optional.of(events.get(0).uuid()), events.get(1).parentUuid());
    }

    @Test
    void loadMessagesSinceLatestCompactBoundaryKeepsSummaryAndLaterMessagesOnly() {
        SessionStore store = new SessionStore(tempDir);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store, new SessionEventFactory("session-1", "E:/work"));
        ContextSummaryMessage summary = new ContextSummaryMessage("summary", 2, Instant.EPOCH);

        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("old"))),
                new PersistenceAction.AppendCompactBoundaryAction(summary,
                        new CompactMetadata(CompactTrigger.MANUAL, 100, 20, 2, Instant.EPOCH)),
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("new")))
        )));

        assertEquals(List.of(summary, new UserMessage("new")),
                store.loadMessagesSinceLatestCompactBoundary("session-1", "E:/work"));
    }

    @Test
    void listSessionsByCwdAndReadMetadataUseCwdIsolationAndTitle() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory leftFactory = new SessionEventFactory("left-session", "E:/left");
        SessionEventFactory rightFactory = new SessionEventFactory("right-session", "E:/right");
        store.append(leftFactory.message(new UserMessage("left title")));
        store.append(rightFactory.message(new UserMessage("right title")));

        List<minicode.session.store.SessionMetadata> left = store.listSessionsByCwd("E:/left");

        assertEquals(1, left.size());
        assertEquals("left-session", left.getFirst().sessionId());
        assertEquals(Optional.of("left title"), store.readMetadata("left-session", "E:/left").orElseThrow().title());
        assertTrue(store.readMetadata("right-session", "E:/left").isEmpty());
    }

    @Test
    void cwdDirectoryKeyDoesNotCollideForSimilarPaths() {
        SessionStore store = new SessionStore(tempDir);
        String underscoreCwd = "E:/foo_bar";
        String nestedCwd = "E:/foo/bar";
        store.append(new SessionEventFactory("underscore-session", underscoreCwd)
                .message(new UserMessage("underscore title")));
        store.append(new SessionEventFactory("nested-session", nestedCwd)
                .message(new UserMessage("nested title")));

        var underscoreSessions = store.listSessionsByCwd(underscoreCwd);
        var nestedSessions = store.listSessionsByCwd(nestedCwd);

        assertEquals(List.of("underscore-session"),
                underscoreSessions.stream().map(minicode.session.store.SessionMetadata::sessionId).toList());
        assertEquals(List.of("nested-session"),
                nestedSessions.stream().map(minicode.session.store.SessionMetadata::sessionId).toList());
        assertTrue(store.readMetadata("nested-session", underscoreCwd).isEmpty());
        assertEquals(List.of(new UserMessage("underscore title")),
                store.readAll("underscore-session", underscoreCwd).stream()
                        .map(event -> event.message().orElseThrow())
                        .toList());
    }

    @Test
    void transcriptProjectorBuildsDisplayEntriesWithoutMutatingEvents() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        store.append(factory.message(new UserMessage("hello")));
        store.append(factory.message(new AssistantMessage("answer")));
        store.append(factory.message(new ToolResultMessage("tool-1", "read_file", "output", false)));
        List<SessionEvent> events = store.readAll("session-1", "E:/work");

        List<TranscriptEntry> transcript = new SessionTranscriptProjector().project(events);

        assertEquals(List.of(
                TranscriptEntry.Kind.USER,
                TranscriptEntry.Kind.ASSISTANT,
                TranscriptEntry.Kind.TOOL
        ), transcript.stream().map(TranscriptEntry::kind).toList());
        assertEquals(events, store.readAll("session-1", "E:/work"));
    }

    @Test
    void metaEventDoesNotCarryMessage() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");

        store.append(factory.meta(new RenameDraft("new title")));

        SessionEvent event = store.readAll("session-1", "E:/work").getFirst();
        assertEquals(SessionEventType.RENAME, event.type());
        assertTrue(event.message().isEmpty());
        assertInstanceOf(RenameDraft.class, event.meta().orElseThrow());
        assertFalse(jsonLines().getFirst().contains("\"message\""));
    }

    @Test
    void persistenceRunnerWritesActionsInOrder() {
        SessionStore store = new SessionStore(tempDir);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(store, new SessionEventFactory("session-1", "E:/work"));

        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(new UserMessage("one"))),
                new PersistenceAction.AppendSessionEventAction(new RenameDraft("renamed")),
                new PersistenceAction.AppendMessagesAction(List.of(new AssistantMessage("two")))
        )));

        List<SessionEvent> events = store.readAll("session-1", "E:/work");
        assertEquals(3, events.size());
        assertEquals(new UserMessage("one"), events.get(0).message().orElseThrow());
        assertEquals(SessionEventType.RENAME, events.get(1).type());
        assertEquals(new AssistantMessage("two"), events.get(2).message().orElseThrow());
        assertEquals(Optional.of(events.get(1).uuid()), events.get(2).parentUuid());
    }

    @Test
    void sameSessionIdDifferentCwdIsIsolated() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory leftFactory = new SessionEventFactory("session-1", "E:/left");
        SessionEventFactory rightFactory = new SessionEventFactory("session-1", "E:/right");

        store.append(leftFactory.message(new UserMessage("left")));
        store.append(rightFactory.message(new UserMessage("right")));

        List<SessionEvent> leftEvents = store.readAll("session-1", "E:/left");
        List<SessionEvent> rightEvents = store.readAll("session-1", "E:/right");
        assertEquals(List.of(new UserMessage("left")), leftEvents.stream().map(event -> event.message().orElseThrow()).toList());
        assertEquals(List.of(new UserMessage("right")), rightEvents.stream().map(event -> event.message().orElseThrow()).toList());
    }

    @Test
    void assistantMessageUsageRoundTrips() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        AssistantMessage message = new AssistantMessage("answer", Optional.of(usage()), UsageStaleness.stale("compact"));

        store.append(factory.message(message));

        AssistantMessage restored = assertInstanceOf(AssistantMessage.class,
                store.readAll("session-1", "E:/work").getFirst().message().orElseThrow());
        assertEquals(Optional.of(usage()), restored.providerUsage());
        assertEquals(UsageStaleness.stale("compact"), restored.usageStaleness());
    }

    @Test
    void assistantProgressMessageUsageRoundTrips() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        AssistantProgressMessage message = new AssistantProgressMessage("working", Optional.of(usage()), UsageStaleness.fresh());

        store.append(factory.message(message));

        AssistantProgressMessage restored = assertInstanceOf(AssistantProgressMessage.class,
                store.readAll("session-1", "E:/work").getFirst().message().orElseThrow());
        assertEquals(Optional.of(usage()), restored.providerUsage());
        assertEquals(UsageStaleness.fresh(), restored.usageStaleness());
    }

    @Test
    void assistantToolCallMessageUsageRoundTrips() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");
        AssistantToolCallMessage message = new AssistantToolCallMessage(
                "tool-use-1",
                "read_file",
                JsonNodeFactory.instance.objectNode().put("path", "README.md"),
                Optional.of(usage()),
                UsageStaleness.stale("summary")
        );

        store.append(factory.message(message));

        AssistantToolCallMessage restored = assertInstanceOf(AssistantToolCallMessage.class,
                store.readAll("session-1", "E:/work").getFirst().message().orElseThrow());
        assertEquals(Optional.of(usage()), restored.providerUsage());
        assertEquals(UsageStaleness.stale("summary"), restored.usageStaleness());
    }

    @Test
    void nonAssistantSideMessagesDoNotSerializeUsageFields() {
        SessionStore store = new SessionStore(tempDir);
        SessionEventFactory factory = new SessionEventFactory("session-1", "E:/work");

        store.append(factory.message(new UserMessage("user")));
        store.append(factory.message(new ToolResultMessage("tool-use-1", "read_file", "content", false)));
        store.append(factory.message(new ContextSummaryMessage("summary", 2, Instant.EPOCH)));

        List<String> lines = jsonLines();
        assertEquals(3, lines.size());
        assertTrue(lines.stream().noneMatch(line -> line.contains("providerUsage")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("usageStaleness")));
    }

    private static ProviderUsage usage() {
        return new ProviderUsage(11, 7, 18);
    }

    private List<String> jsonLines() {
        return jsonlFiles().stream()
                .flatMap(path -> {
                    try {
                        return java.nio.file.Files.readAllLines(path).stream();
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                })
                .toList();
    }

    private List<Path> jsonlFiles() {
        try (Stream<Path> paths = java.nio.file.Files.walk(tempDir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
