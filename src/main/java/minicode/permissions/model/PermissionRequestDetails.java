package minicode.permissions.model;

import java.util.List;
import java.util.Objects;

public record PermissionRequestDetails(String title, String body, List<String> facts) {
    public PermissionRequestDetails {
        if (Objects.requireNonNull(title, "title").isBlank()) {
            throw new IllegalArgumentException("details title must not be blank");
        }
        if (Objects.requireNonNull(body, "body").isBlank()) {
            throw new IllegalArgumentException("details body must not be blank");
        }
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
    }

    public static PermissionRequestDetails of(String title, String body) {
        return new PermissionRequestDetails(title, body, List.of());
    }
}
