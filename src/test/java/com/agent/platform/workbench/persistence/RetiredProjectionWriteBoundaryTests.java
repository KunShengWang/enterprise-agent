package com.agent.platform.workbench.persistence;

import com.agent.platform.config.AgentStorageProperties;
import com.agent.platform.workbench.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RetiredProjectionWriteBoundaryTests {
    @ParameterizedTest
    @ValueSource(strings = {"INCIDENT", "RECOVERY_PLAN"})
    void oldWritesAreRejectedBeforeDatabaseAccessAndOldLeaseIsNotReleased(String type) {
        var properties = new AgentStorageProperties();
        properties.getDatasource().setUrl("jdbc:unavailable:no-database-access-allowed");
        var store = new JdbcWorkbenchStore(properties, new ObjectMapper());
        var now = Instant.now();
        var claim = new WorkProjectionClaim(new WorkProjectionSource("w", type, "old"), "owner", 7, now.plusSeconds(30));
        assertTrue(store.claimProjectionSources("owner", now.plusSeconds(30), 1, Set.of(type)).isEmpty());
        assertDoesNotThrow(() -> store.releaseProjectionClaim(claim));
        assertThrows(IllegalArgumentException.class, () -> store.advanceProjectionCursor(claim, 4));
        assertThrows(IllegalArgumentException.class, () -> store.advanceProjectionCursor("w", type, "old", 4));
        var draft = new ProjectedWorkEventDraft(type, "old", "event", 4,
                WorkEventType.valueOf(type + "_EVENT_PROJECTED"), "old", "old", Map.of(), "w", "", now);
        assertThrows(IllegalArgumentException.class, () -> store.appendProjectedEvent(claim, draft));
        assertThrows(IllegalArgumentException.class, () -> store.appendProjectedEvent("w", draft));
        var snapshot = new WorkExecutionProjection(type, "old", 1, 0, "COMPLETED", "",
                WorkControlState.CLOSED, WorkExecutionState.COMPLETED, WorkOutcome.ASSESSED, now, now);
        assertThrows(IllegalArgumentException.class, () -> store.reconcileExecutionState(claim, snapshot));
    }
}
