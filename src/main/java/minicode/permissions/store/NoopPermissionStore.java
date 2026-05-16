package minicode.permissions.store;

import minicode.permissions.model.PermissionResource;

import java.util.List;
import java.util.Optional;

enum NoopPermissionStore implements PermissionStore {
    INSTANCE;

    @Override
    public Optional<PermissionStoreEntry> find(PermissionResource resource) {
        return Optional.empty();
    }

    @Override
    public void save(PermissionStoreEntry entry) {
    }

    @Override
    public List<PermissionStoreEntry> entries() {
        return List.of();
    }
}
