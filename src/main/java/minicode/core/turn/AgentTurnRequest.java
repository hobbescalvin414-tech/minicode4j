package minicode.core.turn;

import minicode.core.message.ChatMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public record AgentTurnRequest(
        String turnId,
        Path cwd,
        String sessionId,
        List<ChatMessage> messages,
        int maxSteps,
        Optional<String> modelName,
        CancellationToken cancellationToken
) {
    public AgentTurnRequest(String turnId, Path cwd, String sessionId, List<ChatMessage> messages, int maxSteps,
                            Optional<String> modelName) {
        this(turnId, cwd, sessionId, messages, maxSteps, modelName, CancellationToken.none());
    }

    public AgentTurnRequest {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        modelName = Objects.requireNonNull(modelName, "modelName");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
