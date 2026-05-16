package minicode.tui;

import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class MiniTuiEventSink implements AgentEventSink {
    private final PrintWriter out;
    private final AgentEventSink delegate;

    public MiniTuiEventSink(OutputStream output, AgentEventSink delegate) {
        this.out = new PrintWriter(Objects.requireNonNull(output, "output"), true, StandardCharsets.UTF_8);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onEvent(AgentEvent event) {
        render(event);
        delegate.onEvent(event);
    }

    private void render(AgentEvent event) {
        switch (event) {
            case AgentEvent.AssistantMessageEvent assistantMessageEvent -> renderMessage(assistantMessageEvent.message());
            case AgentEvent.ToolStartedEvent toolStartedEvent -> {
                String summary = ToolInputSummarizer.summarize(toolStartedEvent.toolName(), toolStartedEvent.input());
                out.println(summary.isBlank()
                        ? "tool_call: " + toolStartedEvent.toolName()
                        : "tool_call: " + toolStartedEvent.toolName() + " " + summary);
            }
            case AgentEvent.ToolFinishedEvent toolFinishedEvent -> out.println("tool_result: "
                    + toolFinishedEvent.toolName() + " "
                    + (toolFinishedEvent.error() ? "error" : "ok"));
            case AgentEvent.AwaitUserEvent awaitUserEvent ->
                    out.println("await_user: " + awaitUserEvent.toolUseId() + " " + oneLine(awaitUserEvent.question()));
            case AgentEvent.TurnCancelledEvent cancelledEvent -> out.println("cancelled: source="
                    + cancelledEvent.cancellation().source()
                    + " phase=" + cancelledEvent.cancellation().phase()
                    + " reason=" + cancelledEvent.cancellation().reason());
            case AgentEvent.AutoCompactEvent autoCompactEvent -> {
                switch (autoCompactEvent.type()) {
                    case STARTED -> out.println("auto_compact: started");
                    case COMPLETED -> autoCompactEvent.result()
                            .flatMap(minicode.context.compact.CompressionResult::boundary)
                            .ifPresent(boundary -> out.println("auto_compact: ok compressed="
                                    + boundary.metadata().messagesCompressed()
                                    + " tokens=" + boundary.metadata().tokensBefore()
                                    + "->" + boundary.metadata().tokensAfter()));
                    case SKIPPED -> out.println("auto_compact: skipped "
                            + autoCompactEvent.reason().orElse("skipped"));
                    case FAILED -> out.println("auto_compact: failed "
                            + autoCompactEvent.reason().orElse("failed"));
                }
            }
            default -> {
            }
        }
    }

    private void renderMessage(ChatMessage message) {
        switch (message) {
            case AssistantMessage assistantMessage -> out.println("assistant: " + assistantMessage.content());
            case AssistantProgressMessage progressMessage -> out.println("progress: " + progressMessage.content());
            case ToolResultMessage toolResultMessage -> {
                if (toolResultMessage.error() && !toolResultMessage.content().isBlank()) {
                    out.println("tool_error: " + oneLine(toolResultMessage.content()));
                    permissionDeniedFeedback(toolResultMessage.content())
                            .ifPresent(feedback -> out.println("deny_feedback: " + oneLine(feedback)));
                }
            }
            default -> {
            }
        }
    }

    private static String oneLine(String value) {
        String collapsed = value.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 500) {
            return collapsed;
        }
        return collapsed.substring(0, 500) + "...";
    }

    private static java.util.Optional<String> permissionDeniedFeedback(String content) {
        String prefix = "Permission denied:";
        String collapsed = content.replaceAll("\\s+", " ").trim();
        if (!collapsed.startsWith(prefix)) {
            return java.util.Optional.empty();
        }
        String feedback = collapsed.substring(prefix.length()).trim();
        return feedback.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(feedback);
    }
}
