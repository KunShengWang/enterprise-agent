package com.agent.platform.procurement;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static com.agent.platform.procurement.ProcurementEvaluation.*;

/** Reads the frozen ground truth; never derives expected answers from Agent execution. */
public final class ProcurementEvaluationDataset {
    private ProcurementEvaluationDataset() { }

    public static List<EvaluationCase> load() throws IOException {
        try (var input = ProcurementEvaluationDataset.class.getResourceAsStream(
                "/procurement/benchmark/procurement-benchmark-v1.json")) {
            if (input == null) throw new IOException("frozen benchmark missing");
            byte[] bytes = input.readAllBytes();
            var root = new ObjectMapper().readTree(bytes);
            String hash = ProcurementEvaluationReports.sha256(bytes);
            String fixtureHash = ProcurementEvaluationReports.sha256(Files.readAllBytes(Path.of(
                    "data/procurement/scenarios/complex_workstation_01.json")));
            List<EvaluationCase> cases = new ArrayList<>();
            for (var item : root.path("cases")) {
                cases.add(new EvaluationCase(SCHEMA, root.path("benchmarkVersion").asText(), hash, fixtureHash,
                        item.path("caseId").asText(), item.path("userMessage").asText(),
                        root.path("providerFixture").deepCopy(), item.path("expectedCase").deepCopy(),
                        item.path("expected").deepCopy()));
            }
            return List.copyOf(cases);
        }
    }
}
