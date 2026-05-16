package minicode.context;

import minicode.context.accounting.TokenAccountingService;
import minicode.context.accounting.TokenAccountingSource;
import minicode.context.stats.ContextStats;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ContextWarningLevel;
import minicode.context.stats.ModelContextWindow;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TokenAccountingServiceTest {
    @Test
    void usesProviderUsageFirstAndEstimatesTail() {
        TokenAccountingService service = new TokenAccountingService();
        List<ChatMessage> messages = List.of(
                new UserMessage("hello"),
                new AssistantMessage("answer", Optional.of(new ProviderUsage(10, 5, 15)), UsageStaleness.fresh()),
                new UserMessage("tail message")
        );

        var result = service.account(messages);

        assertEquals(TokenAccountingSource.PROVIDER_USAGE_WITH_ESTIMATE, result.source());
        assertEquals(15, result.providerUsageTokens());
        assertTrue(result.estimatedTokens() > 0);
        assertFalse(result.isExact());
        assertFalse(result.stale());
        assertEquals(1, result.usageBoundary().orElseThrow().messageIndex());
        assertTrue(result.usageBoundary().orElseThrow().messageId().isEmpty());
        assertTrue(result.totalTokens() > 15);
    }

    @Test
    void fallsBackToEstimateOnlyWhenProviderUsageIsMissingOrStale() {
        TokenAccountingService service = new TokenAccountingService();
        List<ChatMessage> messages = List.of(
                new AssistantMessage("old", Optional.of(new ProviderUsage(10, 5, 15)), UsageStaleness.stale("compact")),
                new UserMessage("estimate this")
        );

        var result = service.account(messages);

        assertEquals(TokenAccountingSource.ESTIMATE_ONLY, result.source());
        assertEquals(0, result.providerUsageTokens());
        assertEquals(result.totalTokens(), result.estimatedTokens());
        assertFalse(result.isExact());
        assertTrue(result.stale());
        assertEquals(Optional.of("compact"), result.reason());
        assertTrue(result.totalTokens() > 0);
    }

    @Test
    void manualCompactStaleReasonPreventsTrustingOldProviderUsage() {
        TokenAccountingService service = new TokenAccountingService();
        List<ChatMessage> messages = List.of(
                new AssistantMessage("old", Optional.of(new ProviderUsage(10, 5, 15)),
                        UsageStaleness.stale("conversation was manually compacted after this provider usage was recorded")),
                new UserMessage("tail")
        );

        var result = service.account(messages);

        assertEquals(TokenAccountingSource.ESTIMATE_ONLY, result.source());
        assertTrue(result.stale());
        assertEquals(Optional.of("conversation was manually compacted after this provider usage was recorded"),
                result.reason());
    }

    @Test
    void providerUsageBoundaryCarriesAssistantToolCallIndexAndToolUseId() {
        TokenAccountingService service = new TokenAccountingService();
        AssistantToolCallMessage toolCall = new AssistantToolCallMessage(
                "tool-use-7",
                "run_command",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                Optional.of(new ProviderUsage(20, 5, 25)),
                UsageStaleness.fresh()
        );

        var result = service.account(List.of(new UserMessage("hello"), toolCall));

        assertEquals(TokenAccountingSource.PROVIDER_USAGE, result.source());
        assertTrue(result.isExact());
        assertEquals(25, result.providerUsageTokens());
        assertEquals(0, result.estimatedTokens());
        assertEquals(1, result.usageBoundary().orElseThrow().messageIndex());
        assertEquals(Optional.of("tool-use-7"), result.usageBoundary().orElseThrow().messageId());
    }

    @Test
    void contextStatsUseEffectiveInputForWarningLevel() {
        ContextStatsCalculator calculator = new ContextStatsCalculator(
                new TokenAccountingService(),
                new ModelContextWindow(100, 20)
        );

        ContextStats stats = calculator.calculate(List.of(new UserMessage("x".repeat(320))));

        assertEquals(80, stats.maxTokens());
        assertEquals(100, stats.contextWindow());
        assertEquals(20, stats.outputReserve());
        assertEquals(80, stats.effectiveInput());
        assertEquals(ContextWarningLevel.BLOCKED, stats.warningLevel());
    }
}
