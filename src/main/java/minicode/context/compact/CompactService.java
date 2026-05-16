package minicode.context.compact;

import minicode.context.accounting.TokenAccountingService;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ChatMessages;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantStep;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CompactService {
    private static final int MIN_KEEP_MESSAGES = 8;
    private static final long MAX_KEEP_TOKENS = 8_000;
    private static final int TOOL_RESULT_SUMMARY_PREVIEW_CHARS = 500;
    private static final String MANUAL_STALE_REASON =
            "conversation was manually compacted after this provider usage was recorded";
    private static final String AUTO_STALE_REASON =
            "conversation was automatically compacted after this provider usage was recorded";

    private final Clock clock;
    private final TokenAccountingService accountingService;

    public CompactService() {
        this(Clock.systemUTC());
    }

    public CompactService(Clock clock) {
        this(clock, new TokenAccountingService());
    }

    CompactService(Clock clock, TokenAccountingService accountingService) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accountingService = Objects.requireNonNull(accountingService, "accountingService");
    }

    public ManualCompactResult compact(CompactRequest request) {
        Objects.requireNonNull(request, "request");
        List<ChatMessage> messages = request.messages();
        if (messages.size() <= MIN_KEEP_MESSAGES + 1) {
            return ManualCompactResult.skipped(messages, "not enough messages to compact");
        }

        int boundary = findRetentionBoundary(messages);
        if (boundary <= 1 || boundary >= messages.size()) {
            return ManualCompactResult.skipped(messages, "no compactable messages");
        }

        List<ChatMessage> messagesToCompress = messages.subList(1, boundary);
        if (messagesToCompress.isEmpty()) {
            return ManualCompactResult.skipped(messages, "no compactable messages");
        }

        long tokensBefore = accountingService.account(messages).totalTokens();
        List<ChatMessage> summaryRequestMessages = List.of(
                new SystemMessage("You summarize MiniCode coding-agent conversation history for future continuation."),
                new UserMessage(buildSummaryPrompt(messagesToText(messagesToCompress)))
        );

        String summaryContent;
        try {
            AgentStep step = request.modelAdapter().next(summaryRequestMessages);
            if (!(step instanceof AssistantStep assistantStep)) {
                return ManualCompactResult.failed(messages, "summary model returned tool calls");
            }
            summaryContent = assistantStep.content().trim();
            if (summaryContent.isBlank()) {
                return ManualCompactResult.failed(messages, "summary model returned empty content");
            }
        } catch (RuntimeException exception) {
            return ManualCompactResult.failed(messages, safeReason(exception));
        }

        ContextSummaryMessage summary = new ContextSummaryMessage(summaryContent, messagesToCompress.size(), now());
        List<ChatMessage> compactedMessages = new ArrayList<>();
        messages.stream().filter(SystemMessage.class::isInstance).forEach(compactedMessages::add);
        compactedMessages.add(summary);
        compactedMessages.addAll(markRetainedUsageStale(messages.subList(boundary, messages.size()),
                request.trigger()));

        long tokensAfter = accountingService.account(compactedMessages).totalTokens();
        CompactMetadata metadata = new CompactMetadata(
                request.trigger(),
                tokensBefore,
                tokensAfter,
                messagesToCompress.size(),
                now()
        );
        return ManualCompactResult.compacted(List.copyOf(compactedMessages),
                new CompressionBoundaryResult(summary, metadata));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String safeReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private int findRetentionBoundary(List<ChatMessage> messages) {
        long tokenSum = 0;
        int boundary = messages.size();
        for (int index = messages.size() - 1; index >= 1; index--) {
            long tokens = accountingService.account(List.of(messages.get(index))).totalTokens();
            if (tokenSum + tokens > MAX_KEEP_TOKENS) {
                break;
            }
            tokenSum += tokens;
            boundary = index;
        }
        int minBoundary = Math.max(1, messages.size() - MIN_KEEP_MESSAGES);
        boundary = Math.min(boundary, minBoundary);
        if (boundary <= 1 && messages.size() > MIN_KEEP_MESSAGES + 1) {
            boundary = Math.max(1, messages.size() - MIN_KEEP_MESSAGES);
        }
        return alignBoundaryToToolRound(messages, boundary);
    }

    private static int alignBoundaryToToolRound(List<ChatMessage> messages, int boundary) {
        int start = 0;
        while (start < messages.size()) {
            int cursor = start;
            if (messages.get(cursor) instanceof AssistantThinkingMessage) {
                cursor++;
            }
            while (cursor < messages.size() && messages.get(cursor) instanceof AssistantToolCallMessage) {
                cursor++;
            }
            while (cursor < messages.size() && messages.get(cursor) instanceof ToolResultMessage) {
                cursor++;
            }
            if (cursor > start && hasToolRound(messages.subList(start, cursor))) {
                if (boundary > start && boundary < cursor) {
                    return start;
                }
                start = cursor;
                continue;
            }
            start++;
        }
        return boundary;
    }

    private static boolean hasToolRound(List<ChatMessage> messages) {
        return messages.stream().anyMatch(message -> message instanceof AssistantToolCallMessage
                || message instanceof ToolResultMessage);
    }

    private static List<ChatMessage> markRetainedUsageStale(List<ChatMessage> messages, CompactTrigger trigger) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        String reason = switch (trigger) {
            case AUTO -> AUTO_STALE_REASON;
            case MANUAL -> MANUAL_STALE_REASON;
            case MICRO -> "conversation was compacted after this provider usage was recorded";
        };
        for (ChatMessage message : messages) {
            result.add(ChatMessages.markUsageStale(message, reason));
        }
        return List.copyOf(result);
    }

    private static String messagesToText(List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message) {
                case UserMessage user -> parts.add("[User]: " + user.content());
                case AssistantMessage assistant -> parts.add("[Assistant]: " + assistant.content());
                case AssistantProgressMessage progress -> parts.add("[Assistant Progress]: " + progress.content());
                case AssistantThinkingMessage ignored ->
                        parts.add("[Assistant Thinking]: provider reasoning block existed and was omitted");
                case AssistantToolCallMessage toolCall ->
                        parts.add("[Tool Call: " + toolCall.toolName() + "]: " + toolCall.input());
                case ToolResultMessage toolResult -> parts.add("[Tool Result: " + toolResult.toolName()
                        + (toolResult.error() ? " ERROR" : "") + "]: " + preview(toolResult.content()));
                case ContextSummaryMessage summary -> parts.add("[Previous Summary]: " + summary.content());
                case SystemMessage ignored -> {
                }
                default -> {
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private static String preview(String content) {
        if (content.length() <= TOOL_RESULT_SUMMARY_PREVIEW_CHARS) {
            return content;
        }
        return content.substring(0, TOOL_RESULT_SUMMARY_PREVIEW_CHARS) + "... (truncated)";
    }

    private static String buildSummaryPrompt(String conversationText) {
        return """
                Summarize the following MiniCode coding-agent conversation history so a future model can continue the same task.

                Preserve:
                - user goals and constraints
                - decisions already made
                - files, commands, tools, and results that matter
                - unresolved tasks and next steps
                - errors or failed attempts that should not be repeated

                Keep the summary concise but operational.

                Conversation:
                %s
                """.formatted(conversationText);
    }
}
