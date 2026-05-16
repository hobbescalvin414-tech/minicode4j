package minicode.context.compact;

import minicode.context.boundary.ContextBoundaryGuard;
import minicode.context.stats.ContextStats;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

public final class AutoCompactController {
    private final CompactService compactService;
    private final AutoCompactPolicy policy;
    private final boolean enabled;
    private int consecutiveFailures;
    private int cooldownRemaining;

    public AutoCompactController(CompactService compactService, AutoCompactPolicy policy) {
        this(compactService, policy, true);
    }

    private AutoCompactController(CompactService compactService, AutoCompactPolicy policy, boolean enabled) {
        this.compactService = Objects.requireNonNull(compactService, "compactService");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.enabled = enabled;
    }

    public static AutoCompactController disabled() {
        return new AutoCompactController(new CompactService(), AutoCompactPolicy.defaults(), false);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean willAttempt(List<ChatMessage> messages, ContextStats stats) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        return enabled
                && stats.effectiveInput() >= policy.minEffectiveInput()
                && stats.utilization() >= policy.utilizationThreshold()
                && ContextBoundaryGuard.isCompactSafeBoundary(actualMessages)
                && cooldownRemaining == 0;
    }

    public AutoCompactResult preflight(List<ChatMessage> messages, ContextStats stats, ModelAdapter modelAdapter) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        if (!enabled) {
            return AutoCompactResult.skipped(actualMessages, "auto compact disabled");
        }
        if (stats.effectiveInput() < policy.minEffectiveInput()) {
            resetFailures();
            return AutoCompactResult.skipped(actualMessages, "effective input window is below auto compact minimum");
        }
        if (stats.utilization() < policy.utilizationThreshold()) {
            resetFailures();
            return AutoCompactResult.skipped(actualMessages, "context utilization is below auto compact threshold");
        }
        if (!ContextBoundaryGuard.isCompactSafeBoundary(actualMessages)) {
            return AutoCompactResult.skipped(actualMessages, "unsafe compact boundary: incomplete tool round");
        }
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return AutoCompactResult.skipped(actualMessages, "auto compact cooldown after previous failure");
        }

        ManualCompactResult result = compactService.compact(new CompactRequest(actualMessages, modelAdapter,
                CompactTrigger.AUTO));
        if (result.status() == CompactStatus.COMPACTED) {
            consecutiveFailures = 0;
            cooldownRemaining = 0;
            return AutoCompactResult.compacted(result.messages(), result.boundary().orElseThrow());
        }
        if (result.status() == CompactStatus.FAILED) {
            recordFailure();
            return AutoCompactResult.failed(actualMessages, result.reason().orElse("auto compact failed"));
        }
        return AutoCompactResult.skipped(actualMessages, result.reason().orElse("auto compact skipped"));
    }

    private void recordFailure() {
        consecutiveFailures = Math.min(policy.maxFailures(), consecutiveFailures + 1);
        cooldownRemaining = policy.failureCooldownPreflights() * consecutiveFailures;
    }

    private void resetFailures() {
        consecutiveFailures = 0;
        cooldownRemaining = 0;
    }
}
