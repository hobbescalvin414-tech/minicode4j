package minicode.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class McpServerConfig {
    private static final Duration DEFAULT_INITIALIZE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final String command;
    private final List<String> args;
    private final String cwd;
    private final Map<String, String> env;
    private final boolean enabled;
    private final Duration initializeTimeout;
    private final Duration callTimeout;

    public McpServerConfig(String command, List<String> args, String cwd, Map<String, String> env,
                           boolean enabled, Duration initializeTimeout, Duration callTimeout) {
        this.command = Objects.requireNonNull(command, "command").trim();
        this.args = List.copyOf(args == null ? List.of() : args);
        this.cwd = cwd;
        this.env = Map.copyOf(env == null ? Map.of() : env);
        this.enabled = enabled;
        this.initializeTimeout = initializeTimeout == null ? DEFAULT_INITIALIZE_TIMEOUT : initializeTimeout;
        this.callTimeout = callTimeout == null ? DEFAULT_CALL_TIMEOUT : callTimeout;
        if (this.initializeTimeout.isNegative() || this.initializeTimeout.isZero()) {
            throw new IllegalArgumentException("initializeTimeout must be positive");
        }
        if (this.callTimeout.isNegative() || this.callTimeout.isZero()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
    }

    public String command() {
        return command;
    }

    public List<String> args() {
        return args;
    }

    public Optional<String> cwd() {
        return Optional.ofNullable(cwd).filter(value -> !value.isBlank());
    }

    public Map<String, String> env() {
        return env;
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration initializeTimeout() {
        return initializeTimeout;
    }

    public Duration callTimeout() {
        return callTimeout;
    }

    public Duration requestTimeout() {
        return callTimeout;
    }

    public String endpointSummary() {
        return (command + " " + String.join(" ", args)).trim();
    }

    public String commandSummary() {
        return endpointSummary();
    }
}
