package minicode.session.runner;

import minicode.core.message.ChatMessage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.store.SessionStore;

import java.util.Objects;

public final class SessionPersistenceRunner {
    private final SessionStore store;
    private final SessionEventFactory factory;

    public SessionPersistenceRunner(SessionStore store, SessionEventFactory factory) {
        this.store = Objects.requireNonNull(store, "store");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public void apply(TurnPersistencePlan plan) {
        for (PersistenceAction action : Objects.requireNonNull(plan, "plan").actions()) {
            switch (action) {
                case PersistenceAction.AppendMessagesAction appendMessages -> {
                    for (ChatMessage message : appendMessages.messages()) {
                        store.append(factory.message(message));
                    }
                }
                case PersistenceAction.AppendCompactBoundaryAction appendCompactBoundary -> {
                    store.append(factory.compactBoundary(appendCompactBoundary.metadata()));
                    store.append(factory.message(appendCompactBoundary.summaryMessage()));
                }
                case PersistenceAction.AppendSessionEventAction appendSessionEvent ->
                        store.append(factory.meta(appendSessionEvent.draft()));
            }
        }
    }
}
