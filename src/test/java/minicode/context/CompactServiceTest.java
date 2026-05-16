package minicode.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.context.compact.CompactRequest;
import minicode.context.compact.CompactService;
import minicode.context.compact.CompactStatus;
import minicode.context.compact.CompactTrigger;
import minicode.context.compact.ManualCompactResult;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-15T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void compactSuccessCreatesContextSummaryAndManualMetadata() {
        RecordingModelAdapter model = new RecordingModelAdapter(new AssistantStep("Summary of earlier work.", AssistantKind.FINAL));
        CompactService service = new CompactService(CLOCK);
        List<ChatMessage> messages = longConversation(14);

        ManualCompactResult result = service.compact(new CompactRequest(messages, model, CompactTrigger.MANUAL));

        assertEquals(CompactStatus.COMPACTED, result.status());
        assertTrue(result.boundary().isPresent());
        ContextSummaryMessage summary = result.boundary().orElseThrow().summaryMessage();
        assertEquals("Summary of earlier work.", summary.content());
        assertTrue(summary.compressedCount() > 0);
        assertEquals(Instant.parse("2026-05-15T00:00:00Z"), summary.timestamp());
        assertEquals(CompactTrigger.MANUAL, result.boundary().orElseThrow().metadata().trigger());
        assertEquals(summary.compressedCount(), result.boundary().orElseThrow().metadata().messagesCompressed());
        assertTrue(result.boundary().orElseThrow().metadata().tokensBefore() > 0);
        assertTrue(result.boundary().orElseThrow().metadata().tokensAfter() > 0);
        assertInstanceOf(SystemMessage.class, result.messages().getFirst());
        assertEquals(summary, result.messages().get(1));
        assertEquals(1, model.calls.size());
        assertEquals(2, model.calls.getFirst().size());
        assertInstanceOf(SystemMessage.class, model.calls.getFirst().getFirst());
        assertInstanceOf(UserMessage.class, model.calls.getFirst().get(1));
    }

    @Test
    void compactSkipsWhenThereAreNotEnoughMessages() {
        CompactService service = new CompactService(CLOCK);
        List<ChatMessage> messages = List.of(new SystemMessage("system"), new UserMessage("hello"));

        ManualCompactResult result = service.compact(new CompactRequest(messages,
                new RecordingModelAdapter(new AssistantStep("unused", AssistantKind.FINAL)), CompactTrigger.MANUAL));

        assertEquals(CompactStatus.SKIPPED, result.status());
        assertEquals(messages, result.messages());
        assertTrue(result.reason().orElseThrow().contains("not enough"));
        assertTrue(result.boundary().isEmpty());
    }

    @Test
    void compactFailsWhenProviderThrows() {
        CompactService service = new CompactService(CLOCK);
        List<ChatMessage> messages = longConversation(14);
        ModelAdapter failing = ignored -> {
            throw new minicode.model.ModelRequestException("provider down", java.util.Optional.of(503), true,
                    java.util.Optional.of("status=503"));
        };

        ManualCompactResult result = service.compact(new CompactRequest(messages, failing, CompactTrigger.MANUAL));

        assertEquals(CompactStatus.FAILED, result.status());
        assertEquals(messages, result.messages());
        assertTrue(result.reason().orElseThrow().contains("provider down"));
        assertTrue(result.boundary().isEmpty());
    }

    @Test
    void compactMarksRetainedAssistantUsageStale() {
        CompactService service = new CompactService(CLOCK);
        minicode.model.ProviderUsage usage = new minicode.model.ProviderUsage(100, 10, 110);
        List<ChatMessage> messages = new ArrayList<>(longConversation(10));
        messages.add(new AssistantMessage("recent assistant",
                java.util.Optional.of(usage), minicode.model.UsageStaleness.fresh()));

        ManualCompactResult result = service.compact(new CompactRequest(List.copyOf(messages),
                new RecordingModelAdapter(new AssistantStep("summary", AssistantKind.FINAL)), CompactTrigger.MANUAL));

        assertEquals(CompactStatus.COMPACTED, result.status());
        AssistantMessage retained = result.messages().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> message.content().equals("recent assistant"))
                .findFirst()
                .orElseThrow();
        assertTrue(retained.usageStaleness().stale());
        assertTrue(retained.usageStaleness().reason().orElseThrow().contains("manually compacted"));
    }

    @Test
    void compactDoesNotSplitToolCallAndResultRound() {
        CompactService service = new CompactService(CLOCK);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        for (int index = 0; index < 10; index++) {
            messages.add(new UserMessage("old-" + index));
            messages.add(new AssistantMessage("answer-" + index));
        }
        messages.add(new AssistantToolCallMessage("tool-1", "read_file",
                JsonNodeFactory.instance.objectNode().put("path", "README.md")));
        messages.add(new ToolResultMessage("tool-1", "read_file", "result", false));
        messages.add(new UserMessage("after tool"));

        ManualCompactResult result = service.compact(new CompactRequest(List.copyOf(messages),
                new RecordingModelAdapter(new AssistantStep("summary", AssistantKind.FINAL)), CompactTrigger.MANUAL));

        assertEquals(CompactStatus.COMPACTED, result.status());
        List<Class<?>> replayTypes = result.messages().stream().map(Object::getClass).toList();
        int toolCallIndex = replayTypes.indexOf(AssistantToolCallMessage.class);
        int toolResultIndex = replayTypes.indexOf(ToolResultMessage.class);
        assertTrue(toolCallIndex < 0 || toolResultIndex == toolCallIndex + 1);
    }

    @Test
    void compactCanSummarizePreviousContextSummary() {
        CompactService service = new CompactService(CLOCK);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new ContextSummaryMessage("previous summary", 10, Instant.EPOCH));
        for (int index = 0; index < 12; index++) {
            messages.add(new UserMessage("user-" + index + " " + "x".repeat(80)));
            messages.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(80)));
        }

        ManualCompactResult result = service.compact(new CompactRequest(List.copyOf(messages),
                new RecordingModelAdapter(new AssistantStep("new summary", AssistantKind.FINAL)), CompactTrigger.MANUAL));

        assertEquals(CompactStatus.COMPACTED, result.status());
        assertEquals("new summary", result.boundary().orElseThrow().summaryMessage().content());
    }

    private static List<ChatMessage> longConversation(int userAssistantPairs) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("fresh system"));
        for (int index = 0; index < userAssistantPairs; index++) {
            messages.add(new UserMessage("user-" + index + " " + "x".repeat(80)));
            messages.add(new AssistantMessage("assistant-" + index + " " + "y".repeat(80)));
        }
        return List.copyOf(messages);
    }

    private static final class RecordingModelAdapter implements ModelAdapter {
        private final AgentStep response;
        private final List<List<ChatMessage>> calls = new ArrayList<>();

        private RecordingModelAdapter(AgentStep response) {
            this.response = response;
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            return response;
        }
    }
}
