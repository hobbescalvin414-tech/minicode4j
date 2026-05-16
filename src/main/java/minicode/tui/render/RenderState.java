package minicode.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RenderState(List<TranscriptBlock> transcript, StatusState status, InputState input, int scrollOffset,
                          Optional<String> contextBadge) {
    public RenderState {
        transcript = List.copyOf(Objects.requireNonNull(transcript, "transcript"));
        status = Objects.requireNonNull(status, "status");
        input = Objects.requireNonNull(input, "input");
        contextBadge = Objects.requireNonNull(contextBadge, "contextBadge");
        if (scrollOffset < 0) {
            throw new IllegalArgumentException("scrollOffset must be non-negative");
        }
    }

    public static RenderState empty() {
        return new RenderState(List.of(), StatusState.empty(), InputState.empty(), 0, Optional.empty());
    }

    public RenderState withTranscript(List<TranscriptBlock> transcript) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState appendTranscript(TranscriptBlock block) {
        ArrayList<TranscriptBlock> next = new ArrayList<>(transcript);
        TranscriptBlock actualBlock = Objects.requireNonNull(block, "block");
        if (actualBlock.toolUseId().isPresent()) {
            String toolUseId = actualBlock.toolUseId().orElseThrow();
            for (int index = 0; index < next.size(); index++) {
                TranscriptBlock existing = next.get(index);
                if (existing.toolUseId().filter(toolUseId::equals).isPresent()
                        && existing.kind() == TranscriptBlock.Kind.TOOL
                        && actualBlock.kind() == TranscriptBlock.Kind.TOOL) {
                    next.set(index, preserveToolSummary(existing, actualBlock));
                    return withTranscript(next);
                }
            }
        }
        for (int index = 0; index < next.size(); index++) {
            TranscriptBlock existing = next.get(index);
            if (existing.id().equals(actualBlock.id())
                    && existing.kind() == TranscriptBlock.Kind.PERMISSION
                    && actualBlock.kind() == TranscriptBlock.Kind.PERMISSION) {
                next.set(index, actualBlock);
                return withTranscript(next);
            }
        }
        if (next.stream().noneMatch(existing -> existing.id().equals(actualBlock.id()))) {
            next.add(actualBlock);
        }
        return withTranscript(next);
    }

    public RenderState appendTranscript(List<TranscriptBlock> blocks) {
        ArrayList<TranscriptBlock> next = new ArrayList<>(transcript);
        next.addAll(Objects.requireNonNull(blocks, "blocks"));
        return withTranscript(next);
    }

    public RenderState withStatus(StatusState status) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState clearStatus() {
        return withStatus(StatusState.empty());
    }

    public RenderState withInput(InputState input) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState withScrollOffset(int scrollOffset) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState withContextBadge(String contextBadge) {
        return new RenderState(transcript, status, input, scrollOffset,
                Objects.requireNonNull(contextBadge, "contextBadge").isBlank()
                        ? Optional.empty()
                        : Optional.of(contextBadge));
    }

    public RenderState clearContextBadge() {
        return new RenderState(transcript, status, input, scrollOffset, Optional.empty());
    }

    private static TranscriptBlock preserveToolSummary(TranscriptBlock existing, TranscriptBlock replacement) {
        String existingText = existing.text();
        String replacementText = replacement.text();
        if (existingText.isBlank() || replacementText.startsWith(existingText)) {
            return replacement;
        }
        String summary = firstLine(existingText);
        if (summary.isBlank() || replacementText.contains(summary)) {
            return replacement;
        }
        return replacement.withText(replacementText.isBlank()
                ? summary
                : summary + System.lineSeparator() + replacementText);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
