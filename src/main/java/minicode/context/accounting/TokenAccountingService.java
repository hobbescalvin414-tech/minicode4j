package minicode.context.accounting;

import minicode.core.message.*;
import minicode.model.ProviderUsage;

import java.util.List;
import java.util.Optional;

public final class TokenAccountingService {
    public TokenAccountingResult account(List<ChatMessage> messages) {
        int boundary = latestFreshProviderUsageBoundary(messages);
        if (boundary >= 0) {
            ProviderUsage usage = providerUsage(messages.get(boundary)).orElseThrow();
            long estimatedTail = estimate(messages.subList(boundary + 1, messages.size()));
            UsageBoundary usageBoundary = new UsageBoundary(boundary, messageBoundaryId(messages.get(boundary)));
            if (estimatedTail > 0) {
                return TokenAccountingResult.providerUsageWithEstimate(
                        usage.inputTokens() + estimatedTail,
                        usage.outputTokens(),
                        usage.totalTokens() + estimatedTail,
                        usage.totalTokens(),
                        estimatedTail,
                        usageBoundary
                );
            }
            return TokenAccountingResult.providerUsage(
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.totalTokens(),
                    usageBoundary
            );
        }
        long estimate = estimate(messages);
        return TokenAccountingResult.estimateOnly(estimate, staleUsageReason(messages));
    }

    private int latestFreshProviderUsageBoundary(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            Optional<ProviderUsage> usage = providerUsage(message);
            if (usage.isPresent() && !usageStaleness(message).stale()) {
                return index;
            }
        }
        return -1;
    }

    private Optional<ProviderUsage> providerUsage(ChatMessage message) {
        return switch (message) {
            case AssistantMessage assistant -> assistant.providerUsage();
            case AssistantProgressMessage progress -> progress.providerUsage();
            case AssistantToolCallMessage toolCall -> toolCall.providerUsage();
            default -> Optional.empty();
        };
    }

    private minicode.model.UsageStaleness usageStaleness(ChatMessage message) {
        return switch (message) {
            case AssistantMessage assistant -> assistant.usageStaleness();
            case AssistantProgressMessage progress -> progress.usageStaleness();
            case AssistantToolCallMessage toolCall -> toolCall.usageStaleness();
            default -> minicode.model.UsageStaleness.fresh();
        };
    }

    private Optional<String> staleUsageReason(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            Optional<ProviderUsage> usage = providerUsage(message);
            minicode.model.UsageStaleness staleness = usageStaleness(message);
            if (usage.isPresent() && staleness.stale()) {
                return staleness.reason().or(() -> Optional.of("provider usage was marked stale"));
            }
        }
        return Optional.empty();
    }

    private Optional<String> messageBoundaryId(ChatMessage message) {
        if (message instanceof AssistantToolCallMessage toolCall) {
            return Optional.of(toolCall.toolUseId());
        }
        return Optional.empty();
    }

    private long estimate(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        long chars = 0;
        for (ChatMessage message : messages) {
            chars += switch (message) {
                case SystemMessage system -> system.content().length();
                case UserMessage user -> user.content().length();
                case AssistantMessage assistant -> assistant.content().length();
                case AssistantProgressMessage progress -> progress.content().length();
                case ToolResultMessage toolResult -> toolResult.content().length();
                case ContextSummaryMessage summary -> summary.content().length();
                case AssistantToolCallMessage toolCall -> toolCall.toolName().length() + toolCall.input().toString().length();
                case AssistantThinkingMessage thinking -> thinking.blocks().toString().length();
                default -> 0;
            };
        }
        return Math.max(1, (chars + 3) / 4);
    }
}
