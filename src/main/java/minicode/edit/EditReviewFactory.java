package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class EditReviewFactory {
    private EditReviewFactory() {
    }

    public static PermissionResource.EditResource create(Path path, String summary,
                                                         Optional<String> beforeContent, String afterContent,
                                                         Optional<String> toolUseId) {
        return review(path, PermissionResource.EditOperation.CREATE, summary, beforeContent, afterContent, toolUseId);
    }

    public static PermissionResource.EditResource overwrite(Path path, String summary,
                                                            Optional<String> beforeContent, String afterContent,
                                                            Optional<String> toolUseId) {
        return review(path, PermissionResource.EditOperation.OVERWRITE, summary, beforeContent, afterContent, toolUseId);
    }

    public static PermissionResource.EditResource edit(Path path, String summary,
                                                       Optional<String> beforeContent, String afterContent,
                                                       Optional<String> toolUseId) {
        return review(path, PermissionResource.EditOperation.EDIT, summary, beforeContent, afterContent, toolUseId);
    }

    public static PermissionResource.EditResource patch(Path path, String summary,
                                                         Optional<String> beforeContent, String afterContent,
                                                         Optional<String> toolUseId) {
        return review(path, PermissionResource.EditOperation.PATCH, summary, beforeContent, afterContent, toolUseId);
    }

    public static PermissionResource.EditResource modify(Path path, String summary,
                                                         Optional<String> beforeContent, String afterContent,
                                                         Optional<String> toolUseId) {
        return review(path, PermissionResource.EditOperation.MODIFY, summary, beforeContent, afterContent, toolUseId);
    }

    public static PermissionResource.EditResource review(Path path, PermissionResource.EditOperation operation,
                                                         String summary, Optional<String> beforeContent,
                                                         String afterContent, Optional<String> toolUseId) {
        return new PermissionResource.EditResource(
                UnifiedDiffBuilder.build(
                        Objects.requireNonNull(path, "path"),
                        Objects.requireNonNull(operation, "operation"),
                        summary,
                        Objects.requireNonNull(beforeContent, "beforeContent"),
                        Objects.requireNonNull(afterContent, "afterContent")
                ),
                Objects.requireNonNull(toolUseId, "toolUseId")
        );
    }
}
