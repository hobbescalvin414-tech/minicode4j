package minicode.context;

import minicode.context.manager.ContextManager;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.context.accounting.TokenAccountingService;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementRecord;
import minicode.tools.result.ToolResultReplacementResult;
import minicode.tools.result.ToolResultReplacementTrigger;
import minicode.tools.result.ToolResultStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void smallToolResultIsNotReplaced() {
        ContextManager manager = manager(100);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", "short", false);

        ToolResultReplacementResult result = manager.replaceLargeToolResult(original);

        assertEquals(original, result.message());
        assertTrue(result.replacement().isEmpty());
    }

    @Test
    void largeToolResultIsPersistedAndReplaced() throws Exception {
        ContextManager manager = manager(10);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", "abcdefghijklmnopqrstuvwxyz", false);

        ToolResultReplacementResult result = manager.replaceLargeToolResult(original);

        ToolResultReplacementRecord replacement = result.replacement().orElseThrow();
        assertTrue(Files.exists(replacement.storageRef().path()));
        assertEquals(original.content(), Files.readString(replacement.storageRef().path()));
        assertEquals(ToolResultReplacementTrigger.SINGLE_RESULT_TOO_LARGE, replacement.trigger());
    }

    @Test
    void replacementContentEntersToolResultMessageContent() {
        ContextManager manager = manager(10);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", "abcdefghijklmnopqrstuvwxyz", false);

        ToolResultReplacementResult result = manager.replaceLargeToolResult(original);

        assertEquals(result.replacement().orElseThrow().replacementContent(), result.message().content());
        assertTrue(result.message().content().contains("<persisted-output"));
        assertTrue(result.message().content().contains("tool-use-1"));
        assertTrue(result.message().content().contains("abcdefghij"));
    }

    @Test
    void storageRefPointsToExistingFile() {
        ContextManager manager = manager(10);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", "abcdefghijklmnopqrstuvwxyz", false);

        ToolResultReplacementResult result = manager.replaceLargeToolResult(original);

        assertTrue(Files.isRegularFile(result.replacement().orElseThrow().storageRef().path()));
    }

    @Test
    void batchBudgetDoesNotReplaceWhenTotalContentIsWithinBudget() {
        ContextManager manager = manager(100, 100);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", "short", false);

        ToolResultBudgetResult result = manager.applyToolResultBudget(List.of(original));

        assertEquals(List.of(original), result.results());
        assertTrue(result.replacements().isEmpty());
    }

    @Test
    void batchBudgetReplacesToolResultsWhenTotalContentExceedsBudget() {
        ContextManager manager = manager(1000, 120);
        ToolResultMessage first = new ToolResultMessage("tool-use-1", "read_file", repeated("a", 100), false);
        ToolResultMessage second = new ToolResultMessage("tool-use-2", "read_file", repeated("b", 100), false);

        ToolResultBudgetResult result = manager.applyToolResultBudget(List.of(first, second));

        assertFalse(result.replacements().isEmpty());
        ToolResultReplacementRecord replacement = result.replacements().getFirst();
        assertEquals(ToolResultReplacementTrigger.BATCH_BUDGET_EXCEEDED, replacement.trigger());
        ToolResultMessage replacedMessage = result.results().stream()
                .filter(message -> message.toolUseId().equals(replacement.toolUseId()))
                .findFirst()
                .orElseThrow();
        assertEquals(replacement.replacementContent(), replacedMessage.content());
    }

    @Test
    void batchReplacementWritesStorageAndIncludesUserVisibleLocationFields() throws Exception {
        ContextManager manager = manager(1000, 50);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "grep_files", repeated("x", 200), false);

        ToolResultBudgetResult result = manager.applyToolResultBudget(List.of(original));

        ToolResultReplacementRecord replacement = result.replacements().getFirst();
        String content = replacement.replacementContent();
        assertTrue(Files.isRegularFile(replacement.storageRef().path()));
        assertEquals(original.content(), Files.readString(replacement.storageRef().path()));
        assertTrue(content.contains("STORAGE_REF: " + replacement.storageRef().id()));
        assertTrue(content.contains("PATH: " + replacement.storageRef().path()));
        assertTrue(content.contains("BYTES: " + replacement.storageRef().bytes()));
        assertTrue(content.contains("ORIGINAL_CHARS: " + original.content().length()));
        assertTrue(content.contains("PREVIEW:"));
        assertTrue(content.contains("xxxxxxxxxx"));
        assertEquals("xxxxxxxxxx", replacement.preview());
        assertEquals(original.content().length(), replacement.originalChars());
        assertEquals(10, replacement.previewChars());
    }

    @Test
    void repeatedBatchBudgetForSameToolResultReusesStoredReplacement() throws Exception {
        ContextManager manager = manager(1000, 50);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "grep_files", repeated("x", 200), false);

        ToolResultBudgetResult first = manager.applyToolResultBudget(List.of(original));
        ToolResultBudgetResult second = manager.applyToolResultBudget(List.of(original));

        ToolResultReplacementRecord firstReplacement = first.replacements().getFirst();
        ToolResultReplacementRecord secondReplacement = second.replacements().getFirst();
        assertEquals(firstReplacement.storageRef(), secondReplacement.storageRef());
        assertEquals(firstReplacement.replacementContent(), secondReplacement.replacementContent());
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void batchBudgetDoesNotRepersistAlreadyReplacedSingleResult() throws Exception {
        ContextManager manager = manager(10, 20);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", repeated("z", 200), false);
        ToolResultReplacementResult singleReplacement = manager.replaceLargeToolResult(original);

        ToolResultBudgetResult result = manager.applyToolResultBudget(List.of(singleReplacement.message()));

        assertEquals(List.of(singleReplacement.message()), result.results());
        assertTrue(result.replacements().isEmpty());
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void replacementMessageCanBeReplayedWithoutReplacementRecord() {
        ContextManager manager = manager(10);
        ToolResultMessage original = new ToolResultMessage("tool-use-1", "read_file", "abcdefghijklmnopqrstuvwxyz", false);

        ToolResultReplacementResult replaced = manager.replaceLargeToolResult(original);
        ToolResultMessage replayed = new ToolResultMessage(
                replaced.message().toolUseId(),
                replaced.message().toolName(),
                replaced.message().content(),
                replaced.message().error()
        );
        ToolResultReplacementResult secondPass = manager.replaceLargeToolResult(replayed);

        assertEquals(replayed, secondPass.message());
        assertTrue(secondPass.replacement().isEmpty());
        assertTrue(replayed.content().startsWith("<persisted-output "));
    }

    @Test
    void microcompactDoesNotClearWhenContextUtilizationIsLow() {
        ContextManager manager = manager(1000);
        List<ChatMessage> messages = List.of(
                new ToolResultMessage("tool-1", "read_file", "old-1", false),
                new ToolResultMessage("tool-2", "run_command", "old-2", false),
                new ToolResultMessage("tool-3", "grep_files", "recent-1", false),
                new ToolResultMessage("tool-4", "list_files", "recent-2", false),
                new ToolResultMessage("tool-5", "edit_file", "audit-result", false)
        );

        var stats = new ContextStatsCalculator(
                new TokenAccountingService(),
                new ModelContextWindow(10_000, 1_000)
        ).calculate(messages);

        List<ChatMessage> compacted = manager.microcompact(messages, stats);

        assertEquals(messages, compacted);
    }

    @Test
    void microcompactClearsOldCompactableToolResultsOnlyWhenUtilizationIsHighAndMarksUsageStale() {
        ContextManager manager = manager(1000);
        AssistantToolCallMessage toolCallWithUsage = new AssistantToolCallMessage(
                "tool-0",
                "read_file",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                java.util.Optional.of(new ProviderUsage(100, 10, 110)),
                UsageStaleness.fresh()
        );
        List<ChatMessage> messages = List.of(
                toolCallWithUsage,
                new ToolResultMessage("tool-1", "read_file", "x".repeat(100), false),
                new ToolResultMessage("tool-2", "run_command", "y".repeat(100), false),
                new ToolResultMessage("tool-3", "grep_files", "recent-1", false),
                new ToolResultMessage("tool-4", "list_files", "recent-2", false),
                new ToolResultMessage("tool-5", "edit_file", "audit-result", false)
        );
        var stats = new ContextStatsCalculator(
                new TokenAccountingService(),
                new ModelContextWindow(120, 20)
        ).calculate(messages);

        List<ChatMessage> compacted = manager.microcompact(messages, stats);

        assertEquals(messages.size(), compacted.size());
        AssistantToolCallMessage staleToolCall = assertInstanceOf(AssistantToolCallMessage.class, compacted.getFirst());
        assertTrue(staleToolCall.usageStaleness().stale());
        assertTrue(staleToolCall.usageStaleness().reason().orElseThrow().contains("microcompact"));
        assertTrue(((ToolResultMessage) compacted.get(1)).content().contains("Output cleared"));
        assertEquals("y".repeat(100), ((ToolResultMessage) compacted.get(2)).content());
        assertEquals("recent-1", ((ToolResultMessage) compacted.get(3)).content());
        assertEquals("recent-2", ((ToolResultMessage) compacted.get(4)).content());
        assertEquals("audit-result", ((ToolResultMessage) compacted.get(5)).content());
    }

    @Test
    void microcompactReturnsNewMessagesButDoesNotPersistAnythingByItself() throws Exception {
        ContextManager manager = manager(1000);
        List<ChatMessage> messages = List.of(
                new ToolResultMessage("tool-1", "read_file", "x".repeat(100), false),
                new ToolResultMessage("tool-2", "run_command", "y".repeat(100), false),
                new ToolResultMessage("tool-3", "grep_files", "z".repeat(100), false),
                new ToolResultMessage("tool-4", "list_files", "recent-1", false),
                new ToolResultMessage("tool-5", "read_file", "recent-2", false)
        );
        var stats = new ContextStatsCalculator(
                new TokenAccountingService(),
                new ModelContextWindow(120, 20)
        ).calculate(messages);

        List<ChatMessage> compacted = manager.microcompact(messages, stats);

        assertNotEquals(messages, compacted);
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    private ContextManager manager(int threshold) {
        return new ContextManager(new ToolResultStorage(tempDir), threshold, 10);
    }

    private ContextManager manager(int threshold, int batchBudget) {
        return new ContextManager(new ToolResultStorage(tempDir), threshold, batchBudget, 10);
    }

    private static String repeated(String value, int count) {
        return value.repeat(count);
    }
}
