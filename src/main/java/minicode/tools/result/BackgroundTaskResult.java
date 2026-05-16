package minicode.tools.result;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record BackgroundTaskResult(String taskId, BackgroundTaskType type, String command, String cwd,
                                   Optional<Long> pid, BackgroundTaskStatus status, Instant startedAt,
                                   Optional<Instant> endedAt, Optional<Integer> exitCode,
                                   Optional<String> outputRef, Optional<String> errorSummary) {
    public BackgroundTaskResult {
        requireText(taskId, "taskId");
        type = Objects.requireNonNull(type, "type");
        requireText(command, "command");
        requireText(cwd, "cwd");
        pid = Objects.requireNonNull(pid, "pid");
        status = Objects.requireNonNull(status, "status");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        endedAt = Objects.requireNonNull(endedAt, "endedAt");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        outputRef = Objects.requireNonNull(outputRef, "outputRef");
        errorSummary = Objects.requireNonNull(errorSummary, "errorSummary");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
