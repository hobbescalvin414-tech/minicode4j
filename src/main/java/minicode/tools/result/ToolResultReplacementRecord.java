package minicode.tools.result;

import java.util.Objects;

public record ToolResultReplacementRecord(String toolUseId, String toolName, ToolResultReplacementTrigger trigger,
                                          ToolResultStorageRef storageRef, String replacementContent,
                                          String preview, long originalChars, long previewChars,
                                          long replacementLength) {
    public ToolResultReplacementRecord(String toolUseId, String toolName, ToolResultReplacementTrigger trigger,
                                       ToolResultStorageRef storageRef, String replacementContent,
                                       long originalLength, long replacementLength) {
        this(toolUseId, toolName, trigger, storageRef, replacementContent, "", originalLength, 0, replacementLength);
    }

    public ToolResultReplacementRecord {
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        trigger = Objects.requireNonNull(trigger, "trigger");
        storageRef = Objects.requireNonNull(storageRef, "storageRef");
        requireText(replacementContent, "replacementContent");
        preview = Objects.requireNonNull(preview, "preview");
        if (originalChars < 0 || previewChars < 0 || replacementLength < 0) {
            throw new IllegalArgumentException("lengths must be non-negative");
        }
    }

    public long originalLength() {
        return originalChars;
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
