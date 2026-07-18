package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.model.BrokerObservation;
import com.agent.platform.ordercare.incident.model.EvidenceCandidate;
import com.agent.platform.ordercare.incident.model.EvidenceClass;
import com.agent.platform.ordercare.incident.model.EvidenceGap;
import com.agent.platform.ordercare.incident.model.EvidenceStatus;
import com.agent.platform.ordercare.incident.model.EvidenceSubtype;
import com.agent.platform.ordercare.incident.model.IncidentMqFactsResult;
import com.agent.platform.ordercare.incident.tool.IncidentToolCatalog;
import com.agent.platform.runtime.DefaultAgentCapabilityRegistry;
import com.agent.platform.runtime.ToolExecutionRecord;
import com.agent.platform.runtime.ToolExecutionState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将可信只读 ToolExecution 投影为 FACT；模型文本永远不会经过这个入口升级为事实。 */
@Component
public class IncidentEvidenceProjector {

    private final ObjectMapper objectMapper;

    public IncidentEvidenceProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvidenceCandidate> project(List<ToolExecutionRecord> executions) {
        if (executions == null || executions.isEmpty()) {
            return List.of();
        }
        List<EvidenceCandidate> candidates = new ArrayList<>();
        executions.stream()
                .filter(execution -> execution != null
                        && execution.state() == ToolExecutionState.SUCCEEDED
                        && execution.result() != null
                        && execution.result().success())
                .sorted(java.util.Comparator.comparing(ToolExecutionRecord::createdAt))
                .forEach(execution -> candidates.addAll(project(execution)));
        return List.copyOf(candidates);
    }

    public List<EvidenceGap> projectGaps(List<ToolExecutionRecord> executions) {
        if (executions == null || executions.isEmpty()) {
            return List.of();
        }
        List<EvidenceGap> gaps = new ArrayList<>();
        executions.stream()
                .filter(execution -> execution != null
                        && IncidentToolCatalog.MQ_FACTS.equals(execution.toolName())
                        && execution.state() == ToolExecutionState.SUCCEEDED
                        && execution.result() != null
                        && execution.result().success())
                .sorted(java.util.Comparator.comparing(ToolExecutionRecord::createdAt))
                .forEach(execution -> {
                    IncidentMqFactsResult result = objectMapper.readValue(
                            execution.result().content(), IncidentMqFactsResult.class);
                    gaps.addAll(result.evidenceGaps());
                });
        return List.copyOf(gaps);
    }

    private List<EvidenceCandidate> project(ToolExecutionRecord execution) {
        return switch (execution.toolName()) {
            case IncidentToolCatalog.ORDER_FACTS -> List.of(envelopeEvidence(
                    execution, EvidenceSubtype.ORDER_STATUS_SET, "floworder-order"));
            case IncidentToolCatalog.INVENTORY_FACTS -> List.of(
                    envelopeEvidence(execution, EvidenceSubtype.INVENTORY_DEDUCT_SET, "floworder-inventory"),
                    envelopeEvidence(execution, EvidenceSubtype.INVENTORY_INVARIANT, "floworder-inventory"));
            case IncidentToolCatalog.MQ_FACTS -> mqEvidence(execution);
            case DefaultAgentCapabilityRegistry.KNOWLEDGE_SEARCH -> List.of(sopEvidence(execution));
            default -> List.of();
        };
    }

    @SuppressWarnings("unchecked")
    private EvidenceCandidate envelopeEvidence(ToolExecutionRecord execution,
                                               EvidenceSubtype subtype,
                                               String sourceSystem) {
        Map<String, Object> envelope = readMap(execution.result().content());
        Map<String, Object> facts = new LinkedHashMap<>();
        Object rawFacts = envelope.get("facts");
        if (rawFacts instanceof Map<?, ?> map) {
            facts.putAll((Map<String, Object>) map);
        }
        facts.put("scopeHash", string(envelope.get("scopeHash")));
        facts.put("truncated", Boolean.TRUE.equals(envelope.get("truncated")));
        facts.put("missingRequestIds", envelope.getOrDefault("missingRequestIds", List.of()));
        return candidate(
                execution, subtype, sourceSystem,
                string(envelope.getOrDefault("sourceReference", execution.toolName())),
                observedAt(envelope.get("observedAt"), execution.updatedAt()),
                facts
        );
    }

    private List<EvidenceCandidate> mqEvidence(ToolExecutionRecord execution) {
        IncidentMqFactsResult result = objectMapper.readValue(execution.result().content(), IncidentMqFactsResult.class);
        List<EvidenceCandidate> candidates = new ArrayList<>();
        if (result.deadLetterFacts() != null) {
            LinkedHashMap<String, Object> facts = new LinkedHashMap<>(
                    objectMapper.convertValue(result.deadLetterFacts().facts(), Map.class));
            facts.put("scopeHash", result.deadLetterFacts().scopeHash());
            facts.put("truncated", result.deadLetterFacts().truncated());
            facts.put("missingRequestIds", result.deadLetterFacts().missingRequestIds());
            candidates.add(candidate(
                    execution,
                    EvidenceSubtype.DEAD_LETTER_SET,
                    result.deadLetterFacts().sourceSystem(),
                    result.deadLetterFacts().sourceReference(),
                    result.deadLetterFacts().observedAt().toInstant(),
                    facts
            ));
        }
        BrokerObservation broker = result.brokerObservation();
        if (broker != null && "AVAILABLE".equalsIgnoreCase(broker.status())) {
            LinkedHashMap<String, Object> facts = new LinkedHashMap<>();
            facts.put("status", broker.status());
            facts.put("queues", broker.queues());
            facts.put("runtimeSignals", broker.runtimeSignals());
            facts.put("errorCode", broker.errorCode() == null ? "" : broker.errorCode());
            facts.put("scopeHash", string(execution.result().metadata().get("scopeHash")));
            facts.put("truncated", false);
            candidates.add(candidate(
                    execution,
                    EvidenceSubtype.QUEUE_RUNTIME_STATUS,
                    "rabbitmq-management",
                    "rabbitmq:queues:" + string(execution.result().metadata().get("snapshotId")),
                    broker.observedAt().toInstant(),
                    facts
            ));
        }
        return List.copyOf(candidates);
    }

    private EvidenceCandidate sopEvidence(ToolExecutionRecord execution) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("guidance", execution.result().content());
        facts.put("sources", execution.result().metadata().getOrDefault("sources", List.of()));
        facts.put("enoughEvidence", execution.result().metadata().getOrDefault("enoughEvidence", false));
        facts.put("scopeHash", string(execution.request().arguments().get("scopeHash")));
        facts.put("truncated", false);
        return candidate(
                execution,
                EvidenceSubtype.SOP_GUIDANCE,
                "enterprise-rag",
                "knowledge-search:" + execution.toolCallId(),
                execution.updatedAt(),
                facts
        );
    }

    private EvidenceCandidate candidate(ToolExecutionRecord execution,
                                        EvidenceSubtype subtype,
                                        String sourceSystem,
                                        String sourceReference,
                                        Instant observedAt,
                                        Map<String, Object> facts) {
        return new EvidenceCandidate(
                EvidenceClass.FACT,
                subtype,
                sourceSystem == null || sourceSystem.isBlank() ? "unknown-readonly-source" : sourceSystem,
                sourceReference == null || sourceReference.isBlank()
                        ? execution.toolName() + ":" + execution.toolCallId()
                        : sourceReference,
                Map.of(
                        "toolCallId", execution.toolCallId(),
                        "snapshotId", string(execution.request().arguments().get("snapshotId"))
                ),
                observedAt == null ? execution.updatedAt() : observedAt,
                facts,
                EvidenceStatus.ACCEPTED,
                "",
                "evidence:" + execution.toolCallId() + ":" + subtype.name()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    private Instant observedAt(Object raw, Instant fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(raw)).toInstant();
        }
        catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
