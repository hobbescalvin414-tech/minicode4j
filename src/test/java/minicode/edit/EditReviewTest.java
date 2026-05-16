package minicode.edit;

import minicode.permissions.model.PermissionResource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EditReviewTest {
    @Test
    void createFileReviewBuildsUnifiedDiffPreview() {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.CREATE,
                "Create src/App.java",
                Optional.empty(),
                "class App {\n}\n"
        );

        assertEquals(Path.of("src/App.java"), review.path());
        assertEquals(PermissionResource.EditOperation.CREATE, review.operation());
        assertEquals("Create src/App.java", review.summary());
        assertEquals(0, review.beforeChars());
        assertEquals("class App {\n}\n".length(), review.afterChars());
        assertFalse(review.beforeExists());
        assertFalse(review.truncated());
        assertTrue(review.reviewFingerprint().length() >= 32);
        assertTrue(review.diffRef().orElseThrow().startsWith("sha256:"));
        assertTrue(review.diffPreview().contains("--- /dev/null"));
        assertTrue(review.diffPreview().contains("+++ b/src/App.java"));
        assertTrue(review.diffPreview().contains("+class App {"));
    }

    @Test
    void overwriteReviewBuildsContextualDiffPreview() {
        String before = """
                line 1
                line 2
                line 3
                old line
                line 5
                line 6
                line 7
                line 8
                line 9
                line 10
                """;
        String after = """
                line 1
                line 2
                line 3
                new line
                line 5
                line 6
                line 7
                line 8
                line 9
                line 10
                """;

        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.OVERWRITE,
                "Replace class body",
                Optional.of(before),
                after
        );

        assertTrue(review.diffPreview().contains("--- a/src/App.java"));
        assertTrue(review.diffPreview().contains("+++ b/src/App.java"));
        assertTrue(review.diffPreview().contains(" line 2"));
        assertTrue(review.diffPreview().contains(" line 3"));
        assertTrue(review.diffPreview().contains("-old line"));
        assertTrue(review.diffPreview().contains("+new line"));
        assertTrue(review.diffPreview().contains(" line 6"));
        assertTrue(review.diffPreview().contains(" line 7"));
        assertFalse(review.diffPreview().contains(" line 8"));
        assertFalse(review.diffPreview().contains("line 10\n"));
        assertFalse(review.truncated());
    }

    @Test
    void overwriteReviewBuildsMultipleHunksForDistantChanges() {
        String before = numberedFile("old first", "old second");
        String after = numberedFile("new first", "new second");

        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.PATCH,
                "Replace distant lines",
                Optional.of(before),
                after
        );

        assertTrue(review.diffPreview().contains("@@ -2,7 +2,7 @@"));
        assertTrue(review.diffPreview().contains("@@ -12,7 +12,7 @@"));
        assertTrue(review.diffPreview().contains("-old first"));
        assertTrue(review.diffPreview().contains("+new first"));
        assertTrue(review.diffPreview().contains("-old second"));
        assertTrue(review.diffPreview().contains("+new second"));
        assertFalse(review.diffPreview().contains(" line 10\n"));
    }

    @Test
    void fingerprintIncludesBeforeExists() {
        EditReview create = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.MODIFY,
                "Write content",
                Optional.empty(),
                "x\n"
        );
        EditReview overwriteEmpty = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.MODIFY,
                "Write content",
                Optional.of(""),
                "x\n"
        );

        assertNotEquals(create.reviewFingerprint(), overwriteEmpty.reviewFingerprint());
        assertFalse(create.beforeExists());
        assertTrue(overwriteEmpty.beforeExists());
    }

    @Test
    void reviewFactoryProducesPermissionResourceWithReviewPayload() {
        PermissionResource.EditResource resource = EditReviewFactory.modify(
                Path.of("src/App.java"),
                "Write content",
                Optional.empty(),
                "x\n",
                Optional.of("tool-use-1")
        );

        assertEquals(Path.of("src/App.java"), resource.path());
        assertEquals(PermissionResource.EditOperation.MODIFY, resource.operation());
        assertTrue(resource.diffRef().orElseThrow().startsWith("sha256:"));
        assertEquals(Optional.of("tool-use-1"), resource.toolUseId());
    }

    @Test
    void unchangedContentProducesStableNoOpDiff() {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.EDIT,
                "No content change",
                Optional.of("same\ncontent\n"),
                "same\ncontent\n"
        );

        assertEquals("""
                --- a/src/App.java
                +++ b/src/App.java
                @@ no changes @@
                """, review.diffPreview());
        assertFalse(review.truncated());
    }

    @Test
    void diffPreviewIsTruncatedWithMarkerWhenLimitIsExceeded() {
        String after = ("line\n").repeat(80);

        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/Large.java"),
                PermissionResource.EditOperation.CREATE,
                "Create large file",
                Optional.empty(),
                after,
                120
        );

        assertTrue(review.truncated());
        assertTrue(review.diffPreview().endsWith("[diff preview truncated]"));
        assertTrue(review.diffPreview().length() <= 120);
        assertEquals(after.length(), review.afterChars());
    }

    @Test
    void diffOutputNormalizesPlatformLineEndings() {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.OVERWRITE,
                "Normalize line endings",
                Optional.of("old\r\nline\r\n"),
                "new\r\nline\r\n"
        );

        assertFalse(review.diffPreview().contains("\r"));
        assertTrue(review.diffPreview().contains("-old"));
        assertTrue(review.diffPreview().contains("+new"));
    }

    private static String numberedFile(String firstReplacement, String secondReplacement) {
        StringBuilder builder = new StringBuilder();
        for (int line = 1; line <= 20; line++) {
            if (line == 5) {
                builder.append(firstReplacement);
            } else if (line == 15) {
                builder.append(secondReplacement);
            } else {
                builder.append("line ").append(line);
            }
            builder.append('\n');
        }
        return builder.toString();
    }
}
