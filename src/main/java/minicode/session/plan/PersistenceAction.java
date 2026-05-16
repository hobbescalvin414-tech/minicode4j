package minicode.session.plan;

import minicode.core.message.ChatMessage;
import minicode.context.compact.CompactMetadata;
import minicode.session.model.MetaSessionEventDraft;

import java.util.List;
import java.util.Objects;

public sealed interface PersistenceAction permits PersistenceAction.AppendMessagesAction,
        PersistenceAction.AppendCompactBoundaryAction, PersistenceAction.AppendSessionEventAction {
    record AppendMessagesAction(List<ChatMessage> messages) implements PersistenceAction {
        public AppendMessagesAction {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("append messages action requires at least one message");
            }
        }
    }

    record AppendCompactBoundaryAction(ChatMessage summaryMessage, CompactMetadata metadata) implements PersistenceAction {
        public AppendCompactBoundaryAction {
            summaryMessage = Objects.requireNonNull(summaryMessage, "summaryMessage");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    record AppendSessionEventAction(MetaSessionEventDraft draft) implements PersistenceAction {
        public AppendSessionEventAction {
            draft = Objects.requireNonNull(draft, "draft");
        }
    }
}
