package minicode.permissions.store;

import minicode.permissions.model.PermissionResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryPermissionStore implements PermissionStore {
    private final Map<PermissionResourceKey, PermissionStoreEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<PermissionStoreEntry> find(PermissionResource resource) {
        return Optional.ofNullable(entries.get(PermissionResourceKey.from(resource)));
    }

    @Override
    public synchronized void save(PermissionStoreEntry entry) {
        PermissionStoreEntry actualEntry = Objects.requireNonNull(entry, "entry");
        entries.put(actualEntry.resourceKey(), actualEntry);
    }

    @Override
    public synchronized List<PermissionStoreEntry> entries() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }
}
