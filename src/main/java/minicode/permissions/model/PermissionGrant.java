package minicode.permissions.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PermissionGrant(PermissionKind kind, PermissionResource resource, PermissionGrantScope scope,
                              PermissionPersistence persistence, Instant grantedAt, Optional<Instant> expiresAt) {
    public PermissionGrant {
        kind = Objects.requireNonNull(kind, "kind");
        resource = Objects.requireNonNull(resource, "resource");
        scope = Objects.requireNonNull(scope, "scope");
        persistence = Objects.requireNonNull(persistence, "persistence");
        grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
