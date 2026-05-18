package minicode.app.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class UiDiffPreviewFactory {
    private UiDiffPreviewFactory() {
    }

    public static UiEvent.DiffPreview fromDiff(String title, String diff, int maxLines) {
        if (maxLines < 0) {
            throw new IllegalArgumentException("maxLines must be non-negative");
        }
        List<String> lines = Arrays.stream(Objects.requireNonNull(diff, "diff")
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .split("\n", -1))
                .limit(maxLines)
                .map(UiSafeText::redact)
                .toList();
        int totalLines = diff.isEmpty() ? 0 : diff.split("\\R", -1).length;
        int hiddenLines = Math.max(0, totalLines - lines.size());
        return new UiEvent.DiffPreview(Objects.requireNonNull(title, "title"), lines, hiddenLines > 0, hiddenLines);
    }
}
