package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementRecommendationDraft;
import com.agent.platform.procurement.model.RejectedCandidate;
import com.agent.platform.procurement.model.SourcingRecommendation;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 只验证 Agent 的选择，不替 Agent 对 Eligible Supplier 做评分或排序。 */
@Service
public class ProcurementRecommendationFinalizer {
    private final ProcurementCaseStore caseStore;
    private final ProcurementDataProvider provider;
    private final ProcurementDecisionEngine decisionEngine;

    @Autowired
    public ProcurementRecommendationFinalizer(ProcurementCaseStore caseStore,
                                              ProcurementDataProvider provider,
                                              ProcurementDecisionEngine decisionEngine) {
        this.caseStore = caseStore;
        this.provider = provider;
        this.decisionEngine = decisionEngine;
    }

    public Finalization finalize(String tenantId, String userId, String conversationId,
                                 ProcurementRecommendationDraft draft) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("tenantId, userId and conversationId are required");
        }
        validateDraft(draft);
        ProcurementCase current = caseStore.findByTenantUserAndConversationId(
                        tenantId.trim(), userId.trim(), conversationId.trim())
                .orElseThrow(() -> new IllegalArgumentException("procurement Case not found"));
        if (current.version() != draft.evaluatedCaseVersion()) {
            throw new ProcurementCaseVersionConflictException(
                    "recommendation was evaluated against version " + draft.evaluatedCaseVersion()
                            + ", current version is " + current.version());
        }
        ProcurementCaseState state = current.state();
        if (!state.missingFields().isEmpty()) {
            throw new IllegalArgumentException("procurement CaseState is incomplete; clarification is required");
        }

        List<SupplierCandidate> candidates = provider.searchSuppliers(state);
        List<com.agent.platform.procurement.model.SupplierOffer> offers = provider.getSupplierOffers(state, candidates);
        ProcurementDecisionEngine.Evaluation evaluation = decisionEngine.evaluate(state, candidates, offers);
        ProcurementDecisionEngine.CandidateResult selected = evaluation.candidates().stream()
                .filter(candidate -> candidate.candidate().supplierId().equals(draft.selectedSupplierId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("selected supplier does not exist in current Provider snapshot"));
        if (!selected.eligible()) {
            throw new IllegalArgumentException("selected supplier is not eligible: " + draft.selectedSupplierId());
        }

        Map<String, SupplierEvidence> evidenceById = new LinkedHashMap<>();
        for (ProcurementDecisionEngine.CandidateResult candidate : evaluation.candidates()) {
            provider.getSupplierEvidence(candidate.candidate().supplierId(), state)
                    .forEach(evidence -> evidenceById.putIfAbsent(evidence.evidenceId(), evidence));
        }
        List<SupplierEvidence> currentEvidence = List.copyOf(evidenceById.values());
        Map<String, List<String>> evidenceRefsBySupplier = new LinkedHashMap<>();
        currentEvidence.forEach(evidence -> evidenceRefsBySupplier
                .computeIfAbsent(evidence.supplierId(), ignored -> new java.util.ArrayList<>())
                .add(evidence.evidenceId()));
        Set<String> eligibleSupplierIds = evaluation.candidates().stream()
                .filter(ProcurementDecisionEngine.CandidateResult::eligible)
                .map(candidate -> candidate.candidate().supplierId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        SourcingRecommendation recommendation = new SourcingRecommendation(
                selected.candidate(),
                evaluation.candidates().stream()
                        .filter(candidate -> candidate.eligible()
                                && !candidate.candidate().supplierId().equals(selected.candidate().supplierId()))
                        .map(ProcurementDecisionEngine.CandidateResult::candidate)
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toMap(SupplierCandidate::supplierId, value -> value,
                                        (left, right) -> left, java.util.LinkedHashMap::new),
                                values -> List.copyOf(values.values()))),
                decisionEngine.matchedConstraints(state, selected.offer()),
                evaluation.candidates().stream().filter(candidate -> !candidate.eligible())
                        .map(candidate -> new RejectedCandidate(candidate.candidate(), candidate.failures(),
                                evidenceRefsBySupplier.getOrDefault(candidate.candidate().supplierId(), List.of())))
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toMap(value -> value.supplier().supplierId(), value -> value,
                                        (left, right) -> left, java.util.LinkedHashMap::new),
                                values -> List.copyOf(values.values()))),
                draft.tradeoffs(), draft.reasons(), draft.risks(), draft.evidenceRefs(), draft.uncertainties(), draft.confidence());
        ProcurementRecommendationVerifier.verify(recommendation, currentEvidence, eligibleSupplierIds);
        return new Finalization(current, recommendation, evaluation, currentEvidence);
    }

    private void validateDraft(ProcurementRecommendationDraft draft) {
        if (draft == null || draft.selectedSupplierId().isBlank()) {
            throw new IllegalArgumentException("selectedSupplierId is required");
        }
        if (draft.evaluatedCaseVersion() < 0) throw new IllegalArgumentException("evaluatedCaseVersion must not be negative");
        if (draft.evidenceRefs().isEmpty()) throw new IllegalArgumentException("evidenceRefs must not be empty");
        if (Double.isNaN(draft.confidence()) || draft.confidence() < 0 || draft.confidence() > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (draft.evidenceRefs().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("evidenceRefs must not be blank");
        if (draft.reasons().isEmpty()) throw new IllegalArgumentException("reasons must not be empty");
        for (List<String> values : List.of(draft.reasons(), draft.tradeoffs(), draft.risks(), draft.uncertainties())) {
            if (values.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("recommendation explanation entries must not be blank");
        }
    }

    public record Finalization(ProcurementCase procurementCase,
                               SourcingRecommendation recommendation,
                               ProcurementDecisionEngine.Evaluation evaluation,
                               List<SupplierEvidence> evidence) {
        public Finalization {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
