package minicode.tui.render;

import minicode.tui.terminal.TerminalSize;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiRendererSnapshotTest {
    @Test
    void renderFrameKeepsTranscriptStatusFooterAndInputAreasStable() {
        RenderState state = RenderState.empty()
                .withTranscript(List.of(
                        TranscriptBlock.user("old user"),
                        TranscriptBlock.assistant("old assistant"),
                        TranscriptBlock.user("new user"),
                        TranscriptBlock.assistant("new assistant")
                ))
                .withStatus(StatusState.thinking())
                .withInput(InputState.of(InputState.Mode.NORMAL, "next task", 9));

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(32, 6));

        assertEquals(List.of(
                "MiniCode                        ",
                "user: new user                  ",
                "new assistant" + " ".repeat(19),
                "--------------------------------",
                "Thinking...                     ",
                "mini-code> next task            "
        ), frame.lines());
        assertEquals(32, frame.width());
        assertEquals(6, frame.height());
        assertEquals(6, frame.cursorRow());
        assertEquals(21, frame.cursorColumn());
        assertFalse(frame.lines().contains("user: old user                  "));
    }

    @Test
    void transientStatusIsNotStoredInTranscriptAndCanBeCleared() {
        RenderState thinking = RenderState.empty()
                .appendTranscript(TranscriptBlock.user("hello"))
                .withStatus(StatusState.thinking());

        RenderState done = thinking.clearStatus();
        RenderFrame frame = new TuiRenderer().render(done, new TerminalSize(24, 5));

        assertEquals(List.of(TranscriptBlock.user("hello")), done.transcript());
        assertTrue(frame.lines().stream().noneMatch(line -> line.contains("Thinking...")));
        assertTrue(frame.lines().stream().anyMatch(line -> line.contains("> ")));
    }

    @Test
    void renderFrameUsesFullTerminalHeightSoInputCursorIsAtBottom() {
        RenderState state = RenderState.empty()
                .appendTranscript(TranscriptBlock.user("hello"))
                .withInput(InputState.empty());

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(20, 8));

        assertEquals(8, frame.height());
        assertEquals("MiniCode" + " ".repeat(12), frame.lines().getFirst());
        assertEquals("-".repeat(20), frame.lines().get(5));
        assertEquals("Ready" + " ".repeat(15), frame.lines().get(6));
        assertEquals("mini-code> " + " ".repeat(9), frame.lines().get(7));
        assertEquals(8, frame.cursorRow());
        assertEquals(12, frame.cursorColumn());
    }

    @Test
    void transcriptViewportSupportsScrollOffsetWithoutDroppingHistory() {
        RenderState state = RenderState.empty()
                .withTranscript(List.of(
                        TranscriptBlock.user("one"),
                        TranscriptBlock.assistant("two"),
                        TranscriptBlock.user("three"),
                        TranscriptBlock.assistant("four")
                ))
                .withScrollOffset(2);

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(30, 6));

        String text = frame.text();
        assertTrue(text.contains("user: one"), text);
        assertTrue(text.contains("two"), text);
        assertFalse(text.contains("user: three"), text);
        assertFalse(text.contains("four"), text);
    }

    @Test
    void askUserBlockSwitchesPromptToAnswerMode() {
        RenderState state = RenderState.empty()
                .appendTranscript(TranscriptBlock.askUser("tool-1", "Which file should I edit?"))
                .withInput(InputState.of(InputState.Mode.AWAITING_ASK_USER, "", 0))
                .withStatus(StatusState.of("Waiting for user answer..."));

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(48, 6));

        String text = frame.text();
        assertTrue(text.contains("ask_user: Which file should I edit?"), text);
        assertTrue(text.contains("Waiting for user answer..."), text);
        assertTrue(text.contains("answer> "), text);
        assertFalse(text.contains("|"), text);
    }

    @Test
    void largeToolOutputIsBoundedAndInputStatusRemainVisible() {
        String longOutput = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(index -> "line-" + index)
                .toList());
        RenderState state = RenderState.empty()
                .appendTranscript(TranscriptBlock.toolResult("tool-1", "run_command", false, longOutput))
                .withStatus(StatusState.of("Running run_command..."))
                .withInput(InputState.of(InputState.Mode.BUSY, "", 0));

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(50, 12));

        String text = frame.text();
        assertTrue(text.contains("tool: run_command ok"), text);
        assertTrue(text.contains("... truncated"), text);
        assertTrue(text.contains("Running run_command..."), text);
        assertTrue(text.contains("busy> "), text);
        assertFalse(text.contains("|"), text);
        assertFalse(text.contains("line-40"), text);
    }

    @Test
    void contextBadgeRendersInStatusAreaButNotTranscript() {
        RenderState state = RenderState.empty()
                .appendTranscript(TranscriptBlock.user("hello"))
                .withContextBadge("context 42%");

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(40, 5));

        assertTrue(frame.text().contains("Ready | context 42%"), frame.text());
        assertEquals(List.of(TranscriptBlock.user("hello")), state.transcript());
    }

    @Test
    void pendingPermissionRendersAsSafeBlockAndPromptMode() {
        RenderState state = RenderState.empty()
                .appendTranscript(TranscriptBlock.permissionAudit("req-1", "pending command execution"))
                .withInput(InputState.of(InputState.Mode.PENDING_PERMISSION, "", 0))
                .withStatus(StatusState.of("Waiting for approval..."));

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(52, 6));

        String text = frame.text();
        assertTrue(text.contains("permission: pending command execution"), text);
        assertTrue(text.contains("permission> "), text);
        assertFalse(text.contains("|"), text);
        assertTrue(text.contains("Waiting for approval..."), text);
    }

    @Test
    void wideCharactersDoNotOverflowPhysicalTerminalColumns() {
        RenderState state = RenderState.empty()
                .appendTranscript(TranscriptBlock.assistant("你好！这是一个贪吃蛇增强版 Java 项目，功能非常丰富。"))
                .withInput(InputState.empty());

        RenderFrame frame = new TuiRenderer().render(state, new TerminalSize(20, 8));

        assertEquals(20, frame.width());
        assertEquals(8, frame.height());
        for (String line : frame.lines()) {
            assertEquals(20, displayWidth(line), line);
        }
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            width += isWide(codePoint) ? 2 : 1;
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || codePoint == 0x3001
                || codePoint == 0x3002;
    }
}
