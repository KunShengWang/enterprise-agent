package com.agent.platform.procurement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit, offline entry point. Input is an artifact file, never a model or business endpoint. */
class ProcurementArtifactReplayTests {
    @Test
    @EnabledIfSystemProperty(named = "evaluation.artifact", matches = ".+")
    void regradesExistingArtifactFile() throws Exception {
        var artifact = ProcurementEvaluationReports.readArtifact(Path.of(System.getProperty("evaluation.artifact")));
        var definition = ProcurementEvaluationDataset.load().stream()
                .filter(c -> c.caseId().equals(artifact.caseId())).findFirst().orElseThrow();
        var report = ProcurementEvaluationReports.regrade(List.of(definition), List.of(artifact));
        ProcurementEvaluationReports.write(Path.of(System.getProperty("evaluation.report",
                "target/procurement-evaluation/replay-report.json")), report);
        assertEquals(ProcurementEvaluation.Status.PASS, report.results().get(0).structuredStatus(), report.toString());
    }
}
