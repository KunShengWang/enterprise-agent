package com.agent.platform.web;

import com.agent.platform.eval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;

class EvalControllerTests {
    private final EvalRunner runner = mock(EvalRunner.class);
    private final EvalCaseRepository cases = mock(EvalCaseRepository.class);
    private final EvalReportRecorder reports = mock(EvalReportRecorder.class);
    private final EvalEventRecorder events = mock(EvalEventRecorder.class);
    private final AdversarialEvalSuite adversarial = mock(AdversarialEvalSuite.class);
    private final WebTestClient client = WebTestClient.bindToController(
            new EvalController(runner, cases, reports, events, adversarial)).controllerAdvice(new com.agent.platform.common.GlobalExceptionHandler()).build();

    @ParameterizedTest
    @ValueSource(strings = {"run", "regression", "adversarial"})
    void publicEvalEndpointsCannotStartGenericRuns(String endpoint) {
        client.post().uri("/api/agent/evals/" + endpoint).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");
        verifyNoInteractions(runner, reports, adversarial);
    }

    @Test
    void genericHistoryEndpointsRemainAvailable() {
        when(cases.list()).thenReturn(List.of());
        when(reports.recent(10)).thenReturn(List.of());
        when(events.snapshot()).thenReturn(List.of());
        when(reports.find("saved")).thenReturn(Optional.of(
                new EvalReport("saved", Instant.now(), 0, 0, 0, 0, 0, 0, 0, 0, null, List.of())));
        for (String path : List.of("cases", "reports", "events")) {
            client.get().uri("/api/agent/evals/" + path).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.data").isArray();
        }
        client.get().uri("/api/agent/evals/reports/saved").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.runId").isEqualTo("saved");
        verifyNoInteractions(runner);
    }

    @Test
    void removedBusinessEvalEndpointCannotRunAnyEvaluation() {
        // Keep the original unmapped-route test independent of the global catch-all advice.
        WebTestClient.bindToController(new EvalController(runner, cases, reports, events, adversarial)).build()
                .post().uri("/api/agent/evals/ordercare/m1").exchange().expectStatus().isNotFound();
        verifyNoInteractions(runner, cases, reports, events, adversarial);
    }
}
