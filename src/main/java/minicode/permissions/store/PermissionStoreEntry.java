package minicode.permissions.store;

import minicode.permissions.model.PermissionKind;

import java.time.Instant;
import java.util.Objects;

public record PermissionStoreEntry(PermissionStoreDecision decision, PermissionKind kind,
                                   PermissionResourceKey resourceKey, Instant createdAt) {
    public PermissionStoreEntry {
        decision = Objects.requireNonNull(decision, "decision");
        kind = Objects.requireNonNull(kind, "kind");
        resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
