package minicode.app.ui;

import java.util.Objects;
import java.util.Optional;

public final class UiAskUserFlow {
    private PendingAskUser pending;

    public UiEvent.AwaitUser start(String toolUseId, String question) {
        pending = new PendingAskUser(requireText(toolUseId, "toolUseId"), requireText(question, "question"));
        return new UiEvent.AwaitUser(pending.toolUseId(), pending.question());
    }

    public AnswerResult handleAnswer(String toolUseId, String text) {
        if (pending == null) {
            return AnswerResult.error("No pending ask_user request.");
        }
        if (!pending.toolUseId().equals(toolUseId)) {
            return AnswerResult.error("ask_user_answer toolUseId does not match pending request.");
        }
        if (Objects.requireNonNull(text, "text").isBlank()) {
            return AnswerResult.error("ask_user_answer text must not be blank.");
        }
        Answer answer = new Answer(pending.toolUseId(), text.trim());
        pending = null;
        return new AnswerResult(Optional.of(answer), Optional.empty());
    }

    public boolean hasPending() {
        return pending != null;
    }

    public UiEvent.Error clearFatal(String message) {
        pending = null;
        return new UiEvent.Error(message, false);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record PendingAskUser(String toolUseId, String question) {
    }

    public record Answer(String toolUseId, String text) {
        public Answer {
            requireText(toolUseId, "toolUseId");
            requireText(text, "text");
        }
    }

    public record AnswerResult(Optional<Answer> answer, Optional<UiEvent.Error> error) {
        public AnswerResult {
            answer = Objects.requireNonNull(answer, "answer");
            error = Objects.requireNonNull(error, "error");
        }

        private static AnswerResult error(String message) {
            return new AnswerResult(Optional.empty(), Optional.of(new UiEvent.Error(message, true)));
        }
    }
}
