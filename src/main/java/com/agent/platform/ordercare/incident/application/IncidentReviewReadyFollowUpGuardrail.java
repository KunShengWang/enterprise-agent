package com.agent.platform.ordercare.incident.application;

import com.agent.platform.guardrail.GuardrailDecision;
import com.agent.platform.guardrail.GuardrailStage;
import com.agent.platform.ordercare.incident.model.IncidentRecord;
import com.agent.platform.ordercare.incident.model.IncidentStatus;
import com.agent.platform.ordercare.incident.persistence.IncidentStore;
import com.agent.platform.runtime.AgentFollowUpGuardrailPolicy;
import com.agent.platform.runtime.AgentFollowUpInput;
import com.agent.platform.runtime.AgentRunRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** 只放行由事故编排器在 REVIEWING 状态生成的 REVIEW_READY 内部续跑指令。 */
@Component
public class IncidentReviewReadyFollowUpGuardrail implements AgentFollowUpGuardrailPolicy {

    static final String REVIEW_READY = "REVIEW_READY";
    static final String SCENARIO_ID = "ordercare-incident-command-v1";

    private final IncidentStore incidentStore;

    public IncidentReviewReadyFollowUpGuardrail(IncidentStore incidentStore) {
        this.incidentStore = incidentStore;
    }

    @Override
    public Optional<GuardrailDecision> evaluate(AgentRunRecord storedRun, AgentFollowUpInput input) {
        if (storedRun == null || storedRun.request() == null || input == null
                || !REVIEW_READY.equals(input.followUpType())
                || !"follow-up-task-v1".equals(input.schemaVersion())
                || !SCENARIO_ID.equals(storedRun.request().scenarioId())
                || input.additionalToolBudget() != 1
                || input.additionalTokenBudget() != 2_000
                || !input.originalTaskId().isBlank()
                || !input.conflictId().isBlank()
                || !input.relatedEvidenceIds().isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> runMetadata = storedRun.request().metadata();
        String incidentId = value(runMetadata, "incidentId");
        if (incidentId.isBlank()
                || !"COMMANDER".equals(value(runMetadata, "runRole"))
                || !incidentId.equals(value(input.metadata(), "incidentId"))
                || !IncidentStatus.REVIEWING.name().equals(value(input.metadata(), "stateGate"))) {
            return Optional.empty();
        }

        IncidentRecord incident = incidentStore.find(incidentId).orElse(null);
        if (incident == null
                || incident.status() != IncidentStatus.REVIEWING
                || !storedRun.runId().equals(incident.commanderRunId())) {
            return Optional.empty();
        }
        return Optional.of(GuardrailDecision.allow(
                GuardrailStage.INPUT,
                "trusted internal REVIEW_READY verified from persisted Commander and Incident state"));
    }

    private String value(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) {
            return "";
        }
        return String.valueOf(values.get(key)).trim();
    }
}
