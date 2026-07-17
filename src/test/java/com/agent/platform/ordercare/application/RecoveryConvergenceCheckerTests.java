package com.agent.platform.ordercare.application;

import com.agent.platform.ordercare.client.FlowOrderClient;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.OrderCareConvergenceResult;
import org.junit.jupiter.api.Test;

import static com.agent.platform.ordercare.OrderCareTestFixtures.proposal;
import static com.agent.platform.ordercare.OrderCareTestFixtures.recoveryCase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryConvergenceCheckerTests {

    @Test
    void waitsDeterministicallyUntilActionAndBusinessEvidenceBothConverge() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        OrderCareProperties properties = new OrderCareProperties();
        properties.setConvergenceMaxAttempts(3);
        properties.setConvergenceIntervalMillis(0);
        when(client.getProposal("prop-1", "trace-1")).thenReturn(
                proposal("APPROVED", "SUBMITTED", "NOT_CONVERGED", false),
                proposal("APPROVED", "SUBMITTED", "RESOLVED", false)
        );
        when(client.inspectCase("REQUEST_ID", "req-1", "trace-1")).thenReturn(
                recoveryCase(20, true, 10),
                recoveryCase(30, true, 20)
        );
        RecoveryConvergenceChecker checker = new RecoveryConvergenceChecker(client, properties);

        OrderCareConvergenceResult result = checker.await("prop-1", "trace-1");

        assertEquals("RESOLVED", result.status());
        assertEquals(2, result.attempts());
        assertTrue(result.deductReleased());
        assertTrue(result.inventoryInvariantOk());
        assertTrue(result.relatedDeadLettersTerminal());
    }

    @Test
    void neverTreatsSubmittedCommandAsResolvedWithoutTerminalBusinessEvidence() {
        FlowOrderClient client = mock(FlowOrderClient.class);
        OrderCareProperties properties = new OrderCareProperties();
        properties.setConvergenceMaxAttempts(2);
        properties.setConvergenceIntervalMillis(0);
        when(client.getProposal("prop-2", "trace-2"))
                .thenReturn(proposal("APPROVED", "SUBMITTED", "NOT_CONVERGED", false));
        when(client.inspectCase("REQUEST_ID", "req-1", "trace-2"))
                .thenReturn(recoveryCase(20, true, 10));
        RecoveryConvergenceChecker checker = new RecoveryConvergenceChecker(client, properties);

        OrderCareConvergenceResult result = checker.await("prop-2", "trace-2");

        assertEquals("NOT_CONVERGED", result.status());
        assertEquals(2, result.attempts());
        assertFalse(result.deductReleased());
        assertFalse(result.relatedDeadLettersTerminal());
    }
}
