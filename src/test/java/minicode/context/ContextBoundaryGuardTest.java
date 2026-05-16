package minicode.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.context.boundary.ContextBoundaryGuard;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBoundaryGuardTest {
    @Test
    void userMessageIsSafeBoundary() {
        assertTrue(ContextBoundaryGuard.isCompactSafeBoundary(List.of(new UserMessage("hello"))));
    }

    @Test
    void completeToolRoundIsSafeBoundary() {
        List<ChatMessage> messages = List.of(
                new UserMessage("read"),
                new AssistantToolCallMessage("tool-1", "read_file",
                        JsonNodeFactory.instance.objectNode().put("path", "README.md")),
                new ToolResultMessage("tool-1", "read_file", "content", false)
        );

        assertTrue(ContextBoundaryGuard.isCompactSafeBoundary(messages));
    }

    @Test
    void danglingToolCallIsNotSafeBoundary() {
        List<ChatMessage> messages = List.of(
                new UserMessage("read"),
                new AssistantToolCallMessage("tool-1", "read_file",
                        JsonNodeFactory.instance.objectNode().put("path", "README.md"))
        );

        assertFalse(ContextBoundaryGuard.isCompactSafeBoundary(messages));
    }

    @Test
    void partialMultiToolRoundIsNotSafeBoundary() {
        List<ChatMessage> messages = List.of(
                new AssistantToolCallMessage("tool-1", "read_file", JsonNodeFactory.instance.objectNode()),
                new AssistantToolCallMessage("tool-2", "grep_files", JsonNodeFactory.instance.objectNode()),
                new ToolResultMessage("tool-1", "read_file", "content", false)
        );

        assertFalse(ContextBoundaryGuard.isCompactSafeBoundary(messages));
    }

    @Test
    void orphanToolResultIsNotSafeBoundary() {
        assertFalse(ContextBoundaryGuard.isCompactSafeBoundary(List.of(
                new ToolResultMessage("tool-1", "read_file", "content", false)
        )));
    }

    @Test
    void resultBeforeToolCallIsNotSafeBoundary() {
        List<ChatMessage> messages = List.of(
                new ToolResultMessage("tool-1", "read_file", "content", false),
                new AssistantToolCallMessage("tool-1", "read_file", JsonNodeFactory.instance.objectNode())
        );

        assertFalse(ContextBoundaryGuard.isCompactSafeBoundary(messages));
    }

    @Test
    void duplicateToolResultIsNotSafeBoundary() {
        List<ChatMessage> messages = List.of(
                new AssistantToolCallMessage("tool-1", "read_file", JsonNodeFactory.instance.objectNode()),
                new ToolResultMessage("tool-1", "read_file", "content", false),
                new ToolResultMessage("tool-1", "read_file", "duplicate", false)
        );

        assertFalse(ContextBoundaryGuard.isCompactSafeBoundary(messages));
    }
}
