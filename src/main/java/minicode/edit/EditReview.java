package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
/**
 * 描述一次待审查的文件修改。
 *
 * <p>EditReview 是文件写入前交给权限系统和用户审查的结构化摘要。
 * 它记录目标路径、操作类型、修改说明、diff 预览、修改前后规模、
 * 截断状态、审查指纹以及可选的完整 diff 引用。写入工具不应绕过
 * 该 review 直接修改文件。</p>
 */

public record EditReview(Path path, PermissionResource.EditOperation operation, String summary,
                         String diffPreview, long beforeChars, long afterChars, boolean beforeExists,
                         boolean truncated, String reviewFingerprint, Optional<String> diffRef) {
    public EditReview {
        path = Objects.requireNonNull(path, "path");
        operation = Objects.requireNonNull(operation, "operation");
        if (Objects.requireNonNull(summary, "summary").isBlank()) {
            throw new IllegalArgumentException("edit summary must not be blank");
        }
        diffPreview = normalizeLineEndings(Objects.requireNonNull(diffPreview, "diffPreview"));
        if (beforeChars < 0) {
            throw new IllegalArgumentException("beforeChars must not be negative");
        }
        if (afterChars < 0) {
            throw new IllegalArgumentException("afterChars must not be negative");
        }
        reviewFingerprint = Objects.requireNonNull(reviewFingerprint, "reviewFingerprint");
        if (reviewFingerprint.isBlank()) {
            throw new IllegalArgumentException("reviewFingerprint must not be blank");
        }
        diffRef = Objects.requireNonNull(diffRef, "diffRef");
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
