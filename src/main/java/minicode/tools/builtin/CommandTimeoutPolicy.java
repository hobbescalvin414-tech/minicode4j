package minicode.tools.builtin;

import java.time.Duration;
import java.util.Objects;

public final class CommandTimeoutPolicy {
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;
    public static final int MAX_TIMEOUT_SECONDS = 60;

    private final Duration defaultTimeout;
    private final Duration maxTimeout;

    public CommandTimeoutPolicy() {
        this(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS), Duration.ofSeconds(MAX_TIMEOUT_SECONDS));
    }

    public CommandTimeoutPolicy(Duration defaultTimeout, Duration maxTimeout) {
        this.defaultTimeout = requirePositive(defaultTimeout, "defaultTimeout");
        this.maxTimeout = requirePositive(maxTimeout, "maxTimeout");
        if (this.defaultTimeout.compareTo(this.maxTimeout) > 0) {
            throw new IllegalArgumentException("defaultTimeout must be <= maxTimeout");
        }
    }

    public Duration timeoutFor(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return defaultTimeout;
        }
        return Duration.ofSeconds(timeoutSeconds);
    }

    public int maxTimeoutSeconds() {
        return Math.toIntExact(maxTimeout.toSeconds());
    }

    private static Duration requirePositive(Duration duration, String name) {
        Duration actualDuration = Objects.requireNonNull(duration, name);
        if (actualDuration.isZero() || actualDuration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return actualDuration;
    }
}
