package minicode.tui.render;

import minicode.tui.terminal.TerminalSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TuiRenderer {
    private static final int CHROME_ROWS = 4;
    private static final int TOOL_PREVIEW_LINES = 7;

    public RenderFrame render(RenderState state, TerminalSize size) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(size, "size");
        int width = size.columns();
        List<String> transcriptLines = renderTranscriptLines(state, width);
        int height = size.rows();
        int transcriptRows = Math.max(1, height - CHROME_ROWS);
        ArrayList<String> lines = new ArrayList<>(height);
        lines.add(fit("MiniCode", width));
        lines.addAll(renderTranscript(state, transcriptLines, width, transcriptRows));
        lines.add(fit("-".repeat(width), width));
        lines.add(fit(statusText(state), width));
        lines.add(fit(inputText(state.input()), width));
        return new RenderFrame(width, height, lines, height, inputCursorColumn(state.input(), width));
    }

    private static List<String> renderTranscriptLines(RenderState state, int width) {
        List<String> rendered = state.transcript().stream()
                .map(TuiRenderer::boundedRenderText)
                .flatMap(text -> wrap(text, width).stream())
                .toList();
        return rendered.isEmpty() ? List.of(blank(width)) : rendered;
    }

    private static String boundedRenderText(TranscriptBlock block) {
        String text = block.renderText();
        if (block.kind() != TranscriptBlock.Kind.TOOL) {
            return text;
        }
        String[] lines = text.replace("\r", "").split("\n", -1);
        if (lines.length <= TOOL_PREVIEW_LINES + 1) {
            return text;
        }
        ArrayList<String> preview = new ArrayList<>();
        for (int index = 0; index < Math.min(lines.length, TOOL_PREVIEW_LINES); index++) {
            preview.add(lines[index]);
        }
        preview.add("... truncated " + (lines.length - TOOL_PREVIEW_LINES) + " lines");
        return String.join("\n", preview);
    }

    private static List<String> renderTranscript(RenderState state, List<String> rendered, int width, int rows) {
        int endExclusive = Math.max(0, rendered.size() - state.scrollOffset());
        int startInclusive = Math.max(0, endExclusive - rows);
        ArrayList<String> viewport = new ArrayList<>(rendered.subList(startInclusive, endExclusive));
        while (viewport.size() < rows) {
            viewport.add(0, blank(width));
        }
        return viewport.stream().map(line -> fit(line, width)).toList();
    }

    private static List<String> wrap(String text, int width) {
        String value = Objects.requireNonNull(text, "text").replace("\r", "");
        if (value.isEmpty()) {
            return List.of("");
        }
        ArrayList<String> lines = new ArrayList<>();
        for (String rawLine : value.split("\n", -1)) {
            if (rawLine.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int lineWidth = 0;
            for (int index = 0; index < rawLine.length(); ) {
                int codePoint = rawLine.codePointAt(index);
                int charWidth = DisplayText.isWide(codePoint) ? 2 : 1;
                if (lineWidth > 0 && lineWidth + charWidth > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                if (charWidth <= width) {
                    line.appendCodePoint(codePoint);
                    lineWidth += charWidth;
                }
                index += Character.charCount(codePoint);
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    private static String fit(String text, int width) {
        StringBuilder value = new StringBuilder();
        int currentWidth = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int charWidth = DisplayText.isWide(codePoint) ? 2 : 1;
            if (currentWidth + charWidth > width) {
                break;
            }
            value.appendCodePoint(codePoint);
            currentWidth += charWidth;
            index += Character.charCount(codePoint);
        }
        return value + " ".repeat(width - currentWidth);
    }

    private static String statusText(RenderState state) {
        String base = state.status().text().orElse("Ready");
        return state.contextBadge()
                .map(badge -> base + " | " + badge)
                .orElse(base);
    }

    private static String inputText(InputState input) {
        String prompt = switch (input.mode()) {
            case NORMAL -> "mini-code> ";
            case BUSY -> "busy> ";
            case AWAITING_ASK_USER -> "answer> ";
            case PENDING_PERMISSION -> "permission> ";
            case PERMISSION_FEEDBACK -> "feedback> ";
        };
        return prompt + input.text();
    }

    private static int inputCursorColumn(InputState input, int width) {
        int promptLength = switch (input.mode()) {
            case NORMAL -> "mini-code> ".length();
            case BUSY -> "busy> ".length();
            case AWAITING_ASK_USER -> "answer> ".length();
            case PENDING_PERMISSION -> "permission> ".length();
            case PERMISSION_FEEDBACK -> "feedback> ".length();
        };
        String visibleBeforeCursor = input.text().substring(0, Math.min(input.cursor(), input.text().length()));
        return Math.min(width, promptLength + DisplayText.width(visibleBeforeCursor) + 1);
    }

    private static String blank(int width) {
        return " ".repeat(width);
    }
}
