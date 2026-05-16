package minicode.permissions;

import minicode.permissions.model.*;
import minicode.permissions.store.JsonPermissionStore;
import minicode.permissions.store.PermissionResourceKey;
import minicode.permissions.store.PermissionStoreDecision;
import minicode.permissions.store.PermissionStoreEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void jsonPermissionStorePersistsAndReadsPathAndCommandEntries() {
        Path file = tempDir.resolve("permissions.json");
        JsonPermissionStore store = new JsonPermissionStore(file);
        PermissionResource.PathResource pathResource = new PermissionResource.PathResource(
                tempDir.resolve("workspace").resolve("notes.txt"),
                PathIntent.READ
        );
        PermissionResource.CommandResource commandResource = new PermissionResource.CommandResource(
                new CommandSignature("mvn", List.of("test")),
                CommandClassification.DEVELOPMENT
        );

        store.save(new PermissionStoreEntry(
                PermissionStoreDecision.ALLOW,
                PermissionKind.PATH,
                PermissionResourceKey.from(pathResource),
                Instant.parse("2026-05-10T00:00:00Z")
        ));
        store.save(new PermissionStoreEntry(
                PermissionStoreDecision.DENY,
                PermissionKind.COMMAND,
                PermissionResourceKey.from(commandResource),
                Instant.parse("2026-05-10T00:00:01Z")
        ));

        JsonPermissionStore restored = new JsonPermissionStore(file);

        assertEquals(PermissionStoreDecision.ALLOW, restored.find(pathResource).orElseThrow().decision());
        assertEquals(PermissionStoreDecision.DENY, restored.find(commandResource).orElseThrow().decision());
        assertTrue(Files.exists(file));
        assertTrue(read(file).contains("\"version\""));
        assertTrue(read(file).contains("\"entries\""));
    }

    @Test
    void savingSameResourceReplacesPriorDecision() {
        JsonPermissionStore store = new JsonPermissionStore(tempDir.resolve("permissions.json"));
        PermissionResource.CommandResource resource = new PermissionResource.CommandResource(
                new CommandSignature("mvn", List.of("test")),
                CommandClassification.DEVELOPMENT
        );

        store.save(new PermissionStoreEntry(PermissionStoreDecision.ALLOW, PermissionKind.COMMAND,
                PermissionResourceKey.from(resource), Instant.parse("2026-05-10T00:00:00Z")));
        store.save(new PermissionStoreEntry(PermissionStoreDecision.DENY, PermissionKind.COMMAND,
                PermissionResourceKey.from(resource), Instant.parse("2026-05-10T00:00:01Z")));

        assertEquals(PermissionStoreDecision.DENY, store.find(resource).orElseThrow().decision());
        assertEquals(1, store.entries().size());
    }

    @Test
    void missingStoreFileStartsEmpty() {
        JsonPermissionStore store = new JsonPermissionStore(tempDir.resolve("permissions.json"));

        assertEquals(Optional.empty(), store.find(new PermissionResource.PathResource(
                tempDir.resolve("missing.txt"),
                PathIntent.READ
        )));
        assertTrue(store.entries().isEmpty());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
