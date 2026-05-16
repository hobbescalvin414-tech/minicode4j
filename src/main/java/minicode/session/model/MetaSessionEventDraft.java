package minicode.session.model;

public sealed interface MetaSessionEventDraft permits RenameDraft, ForkDraft {
    SessionEventType eventType();
}
