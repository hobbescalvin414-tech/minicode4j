package minicode.session.plan;

import java.util.List;
import java.util.Objects;

public record TurnPersistencePlan(List<PersistenceAction> actions) {
    public TurnPersistencePlan {
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }

    public static TurnPersistencePlan empty() {
        return new TurnPersistencePlan(List.of());
    }
}
