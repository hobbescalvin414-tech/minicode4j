package minicode.permissions;

import minicode.edit.EditReview;
import minicode.edit.UnifiedDiffBuilder;
import minicode.permissions.model.PermissionResource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionResourceTest {
    @Test
    void editResourceCarriesStructuredReviewPayload() {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.CREATE,
                "Create src/App.java",
                Optional.empty(),
                "class App {}\n"
        );
        PermissionResource.EditResource resource = new PermissionResource.EditResource(
                review,
                Optional.of("tool-use-1")
        );

        assertEquals(review, resource.review());
        assertEquals(Path.of("src/App.java"), resource.path());
        assertEquals(PermissionResource.EditOperation.CREATE, resource.operation());
        assertEquals("Create src/App.java", resource.summary());
        assertEquals("class App {}\n".length(), resource.afterChars());
        assertEquals(review.diffPreview(), resource.diffPreview());
        assertFalse(resource.originalExists());
        assertTrue(resource.reviewFingerprint().length() >= 32);
        assertTrue(resource.diffRef().orElseThrow().startsWith("sha256:"));
        assertEquals(Optional.of("tool-use-1"), resource.toolUseId());
    }
}
