package minicode.permissions.model;

import minicode.edit.EditReview;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public sealed interface PermissionResource permits PermissionResource.PathResource,
        PermissionResource.CommandResource, PermissionResource.EditResource, PermissionResource.McpToolResource {
    record PathResource(Path path, PathIntent intent) implements PermissionResource {
        public PathResource {
            path = Objects.requireNonNull(path, "path");
            intent = Objects.requireNonNull(intent, "intent");
        }
    }

    record CommandResource(CommandSignature signature, CommandClassification classification) implements PermissionResource {
        public CommandResource {
            signature = Objects.requireNonNull(signature, "signature");
            classification = Objects.requireNonNull(classification, "classification");
        }
    }

    enum EditOperation {
        CREATE,
        OVERWRITE,
        EDIT,
        PATCH,
        MODIFY
    }

    record EditResource(EditReview review, Optional<String> toolUseId) implements PermissionResource {
        public EditResource {
            review = Objects.requireNonNull(review, "review");
            toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        }

        public Path path() {
            return review.path();
        }

        public EditOperation operation() {
            return review.operation();
        }

        public String summary() {
            return review.summary();
        }

        public String diffPreview() {
            return review.diffPreview();
        }

        public long beforeChars() {
            return review.beforeChars();
        }

        public long afterChars() {
            return review.afterChars();
        }

        public boolean truncated() {
            return review.truncated();
        }

        public boolean originalExists() {
            return review.beforeExists();
        }

        public String reviewFingerprint() {
            return review.reviewFingerprint();
        }

        public Optional<String> diffRef() {
            return review.diffRef();
        }
    }

    record McpToolResource(String serverName, String toolName, String wrappedName,
                           String description) implements PermissionResource {
        public McpToolResource {
            serverName = requireText(serverName, "serverName");
            toolName = requireText(toolName, "toolName");
            wrappedName = requireText(wrappedName, "wrappedName");
            description = Objects.requireNonNull(description, "description");
        }

        private static String requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
