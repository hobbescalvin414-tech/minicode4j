package minicode.tools;

import minicode.tools.result.ToolResultReplacementRecord;
import minicode.tools.result.ToolResultReplacementTrigger;
import minicode.tools.result.ToolResultStorageRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultReplacementRecordTest {
    @Test
    void replacementRecordRequiresReplacementContent() {
        ToolResultStorageRef ref = new ToolResultStorageRef("result-1", Path.of("tool-results/result-1.txt"), 42);

        assertThrows(IllegalArgumentException.class, () -> new ToolResultReplacementRecord(
                "tool-use-1",
                "read_file",
                ToolResultReplacementTrigger.SINGLE_RESULT_TOO_LARGE,
                ref,
                "",
                100,
                42
        ));
    }

    @Test
    void replacementResultKeepsToolResultMessageContentObservable() {
        ToolResultStorageRef ref = new ToolResultStorageRef("result-1", Path.of("tool-results/result-1.txt"), 42);
        ToolResultReplacementRecord replacement = new ToolResultReplacementRecord(
                "tool-use-1",
                "read_file",
                ToolResultReplacementTrigger.BATCH_BUDGET_EXCEEDED,
                ref,
                "<persisted-output>preview</persisted-output>",
                100,
                42
        );

        assertEquals("<persisted-output>preview</persisted-output>", replacement.replacementContent());
    }

    @Test
    void replacementRecordExposesStructuredPreviewMetadata() {
        ToolResultStorageRef ref = new ToolResultStorageRef("result-1", Path.of("tool-results/result-1.txt"), 42);
        ToolResultReplacementRecord replacement = new ToolResultReplacementRecord(
                "tool-use-1",
                "read_file",
                ToolResultReplacementTrigger.BATCH_BUDGET_EXCEEDED,
                ref,
                "<persisted-output>preview</persisted-output>",
                "preview",
                100,
                7,
                42
        );

        assertEquals("preview", replacement.preview());
        assertEquals(100, replacement.originalChars());
        assertEquals(7, replacement.previewChars());
        assertEquals(100, replacement.originalLength());
    }
}
