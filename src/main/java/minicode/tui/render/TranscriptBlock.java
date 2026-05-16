package minicode.tui.render;

import java.util.Objects;
import java.util.Optional;

public record TranscriptBlock(Kind kind, String id, String text, Optional<String> toolUseId,
                              Optional<String> toolName, Optional<ToolStatus> toolStatus) {
    public TranscriptBlock {
        kind = Objects.requireNonNull(kind, "kind");
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        text = Objects.requireNonNull(text, "text");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        toolName = Objects.requireNonNull(toolName, "toolName");
        toolStatus = Objects.requireNonNull(toolStatus, "toolStatus");
    }

    public TranscriptBlock(Kind kind, String text) {
        this(kind, kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + text,
                text, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static TranscriptBlock user(String text) {
        return new TranscriptBlock(Kind.USER, text);
    }

    public static TranscriptBlock userAnswer(String text) {
        return new TranscriptBlock(Kind.USER_ANSWER, "user_answer:" + text, text,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static TranscriptBlock assistant(String text) {
        return new TranscriptBlock(Kind.ASSISTANT, text);
    }

    public static TranscriptBlock progress(String text) {
        return new TranscriptBlock(Kind.PROGRESS, text);
    }

    public static TranscriptBlock tool(String text) {
        return new TranscriptBlock(Kind.TOOL, text);
    }

    public static TranscriptBlock toolStarted(String toolUseId, String toolName, String summary) {
        String text = summary == null || summary.isBlank() ? "" : summary;
        return new TranscriptBlock(Kind.TOOL, "tool:" + toolUseId, text,
                Optional.of(toolUseId), Optional.of(toolName), Optional.of(ToolStatus.RUNNING));
    }

    public static TranscriptBlock toolResult(String toolUseId, String toolName, boolean error, String output) {
        return new TranscriptBlock(Kind.TOOL, "tool:" + toolUseId, Objects.requireNonNull(output, "output"),
                Optional.of(toolUseId), Optional.of(toolName),
                Optional.of(error ? ToolStatus.ERROR : ToolStatus.OK));
    }

    public static TranscriptBlock askUser(String toolUseId, String question) {
        return new TranscriptBlock(Kind.ASK_USER, "ask_user:" + toolUseId, question,
                Optional.of(toolUseId), Optional.of("ask_user"), Optional.empty());
    }

    public static TranscriptBlock diagnostic(String text) {
        return new TranscriptBlock(Kind.DIAGNOSTIC, text);
    }

    public static TranscriptBlock compact(String text) {
        return new TranscriptBlock(Kind.COMPACT, text);
    }

    public static TranscriptBlock permissionAudit(String requestId, String text) {
        return new TranscriptBlock(Kind.PERMISSION, "permission:" + requestId,
                text, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public TranscriptBlock withToolResult(boolean error, String output) {
        if (toolUseId.isEmpty() || toolName.isEmpty()) {
            throw new IllegalStateException("not a tool block");
        }
        return toolResult(toolUseId.orElseThrow(), toolName.orElseThrow(), error, output);
    }

    public TranscriptBlock withText(String text) {
        return new TranscriptBlock(kind, id, text, toolUseId, toolName, toolStatus);
    }

    String renderText() {
        return switch (kind) {
            case USER -> "user: " + text;
            case USER_ANSWER -> "answer: " + text;
            case ASSISTANT -> text;
            case PROGRESS -> "progress: " + text;
            case TOOL -> renderToolText();
            case ASK_USER -> "ask_user: " + text;
            case PERMISSION -> "permission: " + text;
            case DIAGNOSTIC -> text;
            case COMPACT -> "compact: " + text;
        };
    }

    private String renderToolText() {
        if (toolName.isEmpty() || toolStatus.isEmpty()) {
            return "tool: " + text;
        }
        String status = switch (toolStatus.orElseThrow()) {
            case RUNNING -> "running";
            case OK -> "ok";
            case ERROR -> "error";
        };
        if (text.isBlank()) {
            return "tool: " + toolName.orElseThrow() + " " + status;
        }
        return "tool: " + toolName.orElseThrow() + " " + status + "\n" + text;
    }

    public enum Kind {
        USER,
        USER_ANSWER,
        ASSISTANT,
        PROGRESS,
        TOOL,
        ASK_USER,
        PERMISSION,
        DIAGNOSTIC,
        COMPACT
    }

    public enum ToolStatus {
        RUNNING,
        OK,
        ERROR
    }
}
