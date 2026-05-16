package minicode.permissions.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.permissions.model.PermissionKind;
import minicode.permissions.model.PermissionResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class JsonPermissionStore implements PermissionStore {
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final Map<PermissionResourceKey, PermissionStoreEntry> entries = new LinkedHashMap<>();
    private boolean loaded;

    public JsonPermissionStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized Optional<PermissionStoreEntry> find(PermissionResource resource) {
        loadIfNeeded();
        return Optional.ofNullable(entries.get(PermissionResourceKey.from(resource)));
    }

    @Override
    public synchronized void save(PermissionStoreEntry entry) {
        loadIfNeeded();
        PermissionStoreEntry actualEntry = Objects.requireNonNull(entry, "entry");
        entries.put(actualEntry.resourceKey(), actualEntry);
        write();
    }

    @Override
    public synchronized List<PermissionStoreEntry> entries() {
        loadIfNeeded();
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            JsonNode entriesNode = root.get("entries");
            if (entriesNode == null || !entriesNode.isArray()) {
                return;
            }
            for (JsonNode entryNode : entriesNode) {
                PermissionStoreEntry entry = readEntry(entryNode);
                entries.put(entry.resourceKey(), entry);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read permission store " + file, exception);
        }
    }

    private static PermissionStoreEntry readEntry(JsonNode entryNode) {
        JsonNode keyNode = entryNode.path("resourceKey");
        PermissionResourceKey key = new PermissionResourceKey(
                requiredText(keyNode, "type"),
                requiredText(keyNode, "fingerprint")
        );
        return new PermissionStoreEntry(
                PermissionStoreDecision.valueOf(requiredText(entryNode, "decision")),
                PermissionKind.valueOf(requiredText(entryNode, "kind")),
                key,
                Instant.parse(requiredText(entryNode, "createdAt"))
        );
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Invalid permission store entry field: " + field);
        }
        return value.asText();
    }

    private void write() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(file.toFile(), toJson());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write permission store " + file, exception);
        }
    }

    private ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);
        ArrayNode entriesNode = root.putArray("entries");
        for (PermissionStoreEntry entry : entries.values()) {
            ObjectNode entryNode = entriesNode.addObject();
            entryNode.put("decision", entry.decision().name());
            entryNode.put("kind", entry.kind().name());
            ObjectNode keyNode = entryNode.putObject("resourceKey");
            keyNode.put("type", entry.resourceKey().type());
            keyNode.put("fingerprint", entry.resourceKey().fingerprint());
            entryNode.put("createdAt", entry.createdAt().toString());
        }
        return root;
    }
}
