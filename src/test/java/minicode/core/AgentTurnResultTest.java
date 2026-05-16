package minicode.core;

import minicode.core.message.AssistantMessage;
import minicode.core.turn.*;
import minicode.session.plan.TurnPersistencePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentTurnResultTest {
    @Test
    void finalResultDoesNotCarryStopDetails() {
        AgentTurnResult result = AgentTurnResult.finalResult(
                List.of(new AssistantMessage("done")),
                TurnPersistencePlan.empty()
        );

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertTrue(result.stopDetails().isEmpty());
    }

    @Test
    void modelErrorRequiresModelErrorDetails() {
        TurnError error = new TurnError("provider timeout", TurnErrorSource.MODEL, true, Optional.empty(), Optional.empty());

        AgentTurnResult result = AgentTurnResult.modelError(
                List.of(),
                TurnPersistencePlan.empty(),
                new ModelErrorDetails(error)
        );

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        assertInstanceOf(ModelErrorDetails.class, result.stopDetails().orElseThrow());
    }

    @Test
    void factoryRejectsMismatchedStopDetails() {
        TurnCancellation cancellation = new TurnCancellation(CancellationSource.USER, CancellationPhase.MODEL_REQUEST, "ctrl-c");

        assertThrows(IllegalArgumentException.class, () -> AgentTurnResult.create(
                List.of(),
                TurnPersistencePlan.empty(),
                AgentTurnStopReason.MODEL_ERROR,
                Optional.of(new CancellationDetails(cancellation))
        ));
    }
}
