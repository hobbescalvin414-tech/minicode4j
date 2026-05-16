package minicode.context.stats;

import minicode.context.accounting.TokenAccountingResult;
import minicode.context.accounting.TokenAccountingService;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

public final class ContextStatsCalculator {
    private final TokenAccountingService accountingService;
    private final ModelContextWindow window;

    public ContextStatsCalculator(TokenAccountingService accountingService, ModelContextWindow window) {
        this.accountingService = Objects.requireNonNull(accountingService, "accountingService");
        this.window = Objects.requireNonNull(window, "window");
    }

    public ContextStats calculate(List<ChatMessage> messages) {
        TokenAccountingResult accounting = accountingService.account(messages);
        double utilization = Math.min(1.0d, (double) accounting.totalTokens() / (double) window.effectiveInput());
        return new ContextStats(accounting, window.contextWindow(), window.outputReserve(), window.effectiveInput(),
                utilization, warningLevel(utilization));
    }

    private static ContextWarningLevel warningLevel(double utilization) {
        if (utilization >= 0.95d) {
            return ContextWarningLevel.BLOCKED;
        }
        if (utilization >= 0.85d) {
            return ContextWarningLevel.CRITICAL;
        }
        if (utilization >= 0.50d) {
            return ContextWarningLevel.WARNING;
        }
        return ContextWarningLevel.NORMAL;
    }
}
