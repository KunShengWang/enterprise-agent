package com.agent.platform.procurement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static com.agent.platform.procurement.ProcurementEvaluation.Status;

/** Opt-in disk replay; never creates new execution evidence. */
class ProcurementAnswerReplayTests {
    @Test
    @EnabledIfSystemProperty(named = "answer.artifact", matches = ".+")
    void regradesSavedAnswerTwiceWithoutExecution() throws Exception {
        Path artifact = Path.of(System.getProperty("answer.artifact"));
        Path fixture = Path.of(System.getProperty("answer.fixture", "data/procurement/scenarios/complex_workstation_01.json"));
        Path report = Path.of(System.getProperty("answer.report", "target/procurement-evaluation/answer-sidecar.json"));
        var saved = ProcurementEvaluationReports.readArtifact(artifact);
        var definition = ProcurementEvaluationDataset.load().stream().filter(c -> c.caseId().equals(saved.caseId())).findFirst().orElseThrow();
        // An explicitly supplied historical fixture is only accepted if its bytes match the saved artifact hash.
        definition = new ProcurementEvaluation.EvaluationCase(definition.schemaVersion(), definition.datasetVersion(), definition.datasetSha256(),
                saved.metadata().get("fixtureSha256"), definition.caseId(), definition.userMessage(), definition.providerFixture(), definition.expectedCase(), definition.expected());
        byte[] before = Files.readAllBytes(artifact);
        var grader = new ProcurementAnswerGrader();
        var first = grader.replay(definition, artifact, fixture, report);
        byte[] firstReport = Files.readAllBytes(report);
        var second = grader.replay(definition, artifact, fixture, report);
        assertEquals(first, second);
        assertArrayEquals(firstReport, Files.readAllBytes(report));
        assertArrayEquals(before, Files.readAllBytes(artifact));
        assertNotEquals(Status.ERROR, first.evaluationStatus(), first.toString());
        assertNotEquals(Status.FAIL, first.evaluationStatus(), first.toString());
        assertEquals(Status.SKIP, first.completeAnswerStatus());
    }
}
