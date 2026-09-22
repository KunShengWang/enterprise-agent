package com.agent.platform.web;

import com.agent.platform.eval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class EvalControllerTests {
    private final EvalRunner runner = mock(EvalRunner.class);
    private final EvalCaseRepository cases = mock(EvalCaseRepository.class);
    private final EvalReportRecorder reports = mock(EvalReportRecorder.class);
    private final EvalEventRecorder events = mock(EvalEventRecorder.class);
    private final AdversarialEvalSuite adversarial = mock(AdversarialEvalSuite.class);
    private final WebTestClient client = WebTestClient.bindToController(
            new EvalController(runner, cases, reports, events, adversarial)).build();

    @ParameterizedTest
    @ValueSource(strings = {"run", "regression", "adversarial"})
    void genericEvalEndpointsStillRunAndRecordReports(String endpoint) {
        var report = new EvalReport("offline-report", Instant.now(), 0, 0, 0, 0, 0, 0, 0, 0, null, List.of());
        when(runner.run(anyList())).thenReturn(report);
        when(adversarial.cases()).thenReturn(List.of());
        client.post().uri("/api/agent/evals/" + endpoint).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.runId").isEqualTo("offline-report");
        verify(runner).run(List.of());
        verify(reports).record(report);
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
        client.post().uri("/api/agent/evals/ordercare/m1").exchange().expectStatus().isNotFound();
        verifyNoInteractions(runner, cases, reports, events, adversarial);
    }
}
