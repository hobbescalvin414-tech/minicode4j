package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class UnifiedDiffBuilder {
    private static final int DEFAULT_MAX_PREVIEW_CHARS = 4096;
    private static final int CONTEXT_LINES = 3;
    private static final int LCS_CUTOFF = 4_000_000;
    private static final String TRUNCATED_MARKER = "[diff preview truncated]";

    private UnifiedDiffBuilder() {
    }

    public static EditReview build(Path path, PermissionResource.EditOperation operation, String summary,
                                   Optional<String> beforeContent, String afterContent) {
        return build(path, operation, summary, beforeContent, afterContent, DEFAULT_MAX_PREVIEW_CHARS);
    }

    public static EditReview build(Path path, PermissionResource.EditOperation operation, String summary,
                                   Optional<String> beforeContent, String afterContent, int maxPreviewChars) {
        Path actualPath = Objects.requireNonNull(path, "path");
        PermissionResource.EditOperation actualOperation = Objects.requireNonNull(operation, "operation");
        if (maxPreviewChars <= 0) {
            throw new IllegalArgumentException("maxPreviewChars must be positive");
        }

        Optional<String> normalizedBefore = Objects.requireNonNull(beforeContent, "beforeContent")
                .map(UnifiedDiffBuilder::normalizeLineEndings);
        String normalizedAfter = normalizeLineEndings(Objects.requireNonNull(afterContent, "afterContent"));
        boolean beforeExists = normalizedBefore.isPresent();
        String preview = renderPreview(actualPath, normalizedBefore, normalizedAfter);
        boolean truncated = preview.length() > maxPreviewChars;
        if (truncated) {
            preview = truncate(preview, maxPreviewChars);
        }

        long beforeChars = normalizedBefore.map(String::length).orElse(0);
        long afterChars = normalizedAfter.length();
        String fingerprint = fingerprint(actualPath, actualOperation, beforeExists, normalizedBefore, normalizedAfter);
        return new EditReview(
                actualPath,
                actualOperation,
                summary,
                preview,
                beforeChars,
                afterChars,
                beforeExists,
                truncated,
                fingerprint,
                Optional.of("sha256:" + fingerprint)
        );
    }

    private static String renderPreview(Path path, Optional<String> beforeContent, String afterContent) {
        String pathText = displayPath(path);
        String normalizedBefore = beforeContent.orElse("");
        if (beforeContent.isPresent() && normalizedBefore.equals(afterContent)) {
            return "--- a/" + pathText + "\n"
                    + "+++ b/" + pathText + "\n"
                    + "@@ no changes @@\n";
        }

        List<String> beforeLines = toLines(normalizedBefore);
        List<String> afterLines = toLines(afterContent);

        if (!beforeContent.isPresent()) {
            return renderCreate(pathText, afterLines);
        }
        if (afterContent.isEmpty()) {
            return renderDelete(pathText, beforeLines);
        }
        return renderMultiHunk(pathText, beforeLines, afterLines);
    }

    private static String renderCreate(String pathText, List<String> afterLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("--- /dev/null\n");
        builder.append("+++ b/").append(pathText).append('\n');
        builder.append(String.format("@@ -0,0 +1,%d @@\n", afterLines.size()));
        for (String line : afterLines) {
            builder.append('+').append(line).append('\n');
        }
        return builder.toString();
    }

    private static String renderDelete(String pathText, List<String> beforeLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(pathText).append('\n');
        builder.append("+++ /dev/null\n");
        builder.append(String.format("@@ -1,%d +0,0 @@\n", beforeLines.size()));
        for (String line : beforeLines) {
            builder.append('-').append(line).append('\n');
        }
        return builder.toString();
    }

    private static String renderMultiHunk(String pathText, List<String> beforeLines, List<String> afterLines) {
        List<DiffEntry> entries = buildEntries(beforeLines, afterLines);
        if (entries.stream().allMatch(entry -> entry.type() == DiffType.KEEP)) {
            return "--- a/" + pathText + "\n"
                    + "+++ b/" + pathText + "\n"
                    + "@@ no changes @@\n";
        }

        List<Range> ranges = buildRanges(entries);
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(pathText).append('\n');
        builder.append("+++ b/").append(pathText).append('\n');
        for (Range range : ranges) {
            List<DiffEntry> slice = entries.subList(range.start(), range.endInclusive() + 1);
            long oldStart = slice.stream().filter(DiffEntry::contributesToOld).findFirst()
                    .map(entry -> entry.oldIndex() + 1L)
                    .orElse(0L);
            long newStart = slice.stream().filter(DiffEntry::contributesToNew).findFirst()
                    .map(entry -> entry.newIndex() + 1L)
                    .orElse(0L);
            long oldCount = slice.stream().filter(DiffEntry::contributesToOld).count();
            long newCount = slice.stream().filter(DiffEntry::contributesToNew).count();

            builder.append(String.format("@@ -%d,%d +%d,%d @@\n", oldStart, oldCount, newStart, newCount));
            for (DiffEntry entry : slice) {
                builder.append(entry.prefix()).append(entry.text()).append('\n');
            }
        }
        return builder.toString();
    }

    private static List<DiffEntry> buildEntries(List<String> beforeLines, List<String> afterLines) {
        if ((long) beforeLines.size() * (long) afterLines.size() > LCS_CUTOFF) {
            return buildGreedyEntries(beforeLines, afterLines);
        }
        int[][] lcs = buildLcsTable(beforeLines, afterLines);
        List<DiffEntry> entries = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < beforeLines.size() || j < afterLines.size()) {
            if (i < beforeLines.size() && j < afterLines.size()
                    && beforeLines.get(i).equals(afterLines.get(j))) {
                entries.add(new DiffEntry(DiffType.KEEP, beforeLines.get(i), i, j));
                i++;
                j++;
                continue;
            }
            if (j < afterLines.size() && (i == beforeLines.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                entries.add(new DiffEntry(DiffType.INSERT, afterLines.get(j), i, j));
                j++;
            } else {
                entries.add(new DiffEntry(DiffType.DELETE, beforeLines.get(i), i, j));
                i++;
            }
        }
        return entries;
    }

    private static List<DiffEntry> buildGreedyEntries(List<String> beforeLines, List<String> afterLines) {
        List<DiffEntry> entries = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < beforeLines.size() || j < afterLines.size()) {
            if (i < beforeLines.size() && j < afterLines.size()
                    && beforeLines.get(i).equals(afterLines.get(j))) {
                entries.add(new DiffEntry(DiffType.KEEP, beforeLines.get(i), i, j));
                i++;
                j++;
                continue;
            }
            if (i < beforeLines.size()) {
                entries.add(new DiffEntry(DiffType.DELETE, beforeLines.get(i), i, j));
                i++;
            }
            if (j < afterLines.size()) {
                entries.add(new DiffEntry(DiffType.INSERT, afterLines.get(j), i, j));
                j++;
            }
        }
        return entries;
    }

    private static int[][] buildLcsTable(List<String> beforeLines, List<String> afterLines) {
        int[][] table = new int[beforeLines.size() + 1][afterLines.size() + 1];
        for (int i = beforeLines.size() - 1; i >= 0; i--) {
            for (int j = afterLines.size() - 1; j >= 0; j--) {
                if (beforeLines.get(i).equals(afterLines.get(j))) {
                    table[i][j] = table[i + 1][j + 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i + 1][j], table[i][j + 1]);
                }
            }
        }
        return table;
    }

    private static List<Range> buildRanges(List<DiffEntry> entries) {
        List<Range> ranges = new ArrayList<>();
        int index = 0;
        while (index < entries.size()) {
            while (index < entries.size() && entries.get(index).type() == DiffType.KEEP) {
                index++;
            }
            if (index >= entries.size()) {
                break;
            }
            int start = Math.max(0, index - CONTEXT_LINES);
            int lastChange = index;
            int end = index;
            int keepTail = 0;
            int cursor = index;
            while (cursor < entries.size()) {
                if (entries.get(cursor).type() == DiffType.KEEP) {
                    keepTail++;
                    if (keepTail > CONTEXT_LINES) {
                        break;
                    }
                } else {
                    lastChange = cursor;
                    keepTail = 0;
                }
                end = cursor;
                cursor++;
            }
            end = Math.min(entries.size() - 1, Math.max(lastChange, end) );
            ranges.add(new Range(start, end));
            index = end + 1;
        }
        return mergeRanges(ranges);
    }

    private static List<Range> mergeRanges(List<Range> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }
        List<Range> merged = new ArrayList<>();
        Range current = ranges.getFirst();
        for (int i = 1; i < ranges.size(); i++) {
            Range next = ranges.get(i);
            if (next.start() <= current.endInclusive() + 1) {
                current = new Range(current.start(), Math.max(current.endInclusive(), next.endInclusive()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static List<String> toLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        return content.lines().toList();
    }

    private static String truncate(String preview, int maxPreviewChars) {
        String marker = "\n" + TRUNCATED_MARKER;
        if (maxPreviewChars <= marker.length()) {
            return marker.substring(0, maxPreviewChars);
        }
        return preview.substring(0, maxPreviewChars - marker.length()) + marker;
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String fingerprint(Path path, PermissionResource.EditOperation operation, boolean beforeExists,
                                      Optional<String> beforeContent, String afterContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(displayPath(path).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(operation.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((byte) (beforeExists ? 1 : 0));
            digest.update((byte) 0);
            digest.update(beforeContent.orElse("").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(afterContent.getBytes(StandardCharsets.UTF_8));
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            builder.append(Character.forDigit((unsigned >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(unsigned & 0xF, 16));
        }
        return builder.toString();
    }

    private enum DiffType {
        KEEP,
        DELETE,
        INSERT
    }

    private record DiffEntry(DiffType type, String text, int oldIndex, int newIndex) {
        boolean contributesToOld() {
            return type == DiffType.KEEP || type == DiffType.DELETE;
        }

        boolean contributesToNew() {
            return type == DiffType.KEEP || type == DiffType.INSERT;
        }

        char prefix() {
            return switch (type) {
                case KEEP -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            };
        }
    }

    private record Range(int start, int endInclusive) {
    }
}
