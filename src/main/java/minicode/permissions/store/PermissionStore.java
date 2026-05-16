package minicode.permissions.store;

import minicode.permissions.model.PermissionKind;
import minicode.permissions.model.PermissionResource;

import java.util.List;
import java.util.Optional;

public interface PermissionStore {
    Optional<PermissionStoreEntry> find(PermissionResource resource);

    void save(PermissionStoreEntry entry);

    List<PermissionStoreEntry> entries();

    default void allow(PermissionKind kind, PermissionResource resource) {
        save(new PermissionStoreEntry(
                PermissionStoreDecision.ALLOW,
                kind,
                PermissionResourceKey.from(resource),
                java.time.Instant.now()
        ));
    }

    default void deny(PermissionKind kind, PermissionResource resource) {
        save(new PermissionStoreEntry(
                PermissionStoreDecision.DENY,
                kind,
                PermissionResourceKey.from(resource),
                java.time.Instant.now()
        ));
    }

    static PermissionStore none() {
        return NoopPermissionStore.INSTANCE;
    }
}
