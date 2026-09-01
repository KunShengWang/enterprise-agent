package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Java 只负责确定性 Eligibility；供应商选择和开放式权衡必须由 Agent 明确提交。 */
@Component
public class ProcurementDecisionEngine {
    public static final java.util.Set<String> SUPPORTED_HARD_CONSTRAINTS = java.util.Set.of("gpuMemoryMinGb");

    public Evaluation evaluate(ProcurementCaseState state, List<SupplierCandidate> candidates, List<SupplierOffer> offers) {
        Map<String, SupplierCandidate> byId = new HashMap<>();
        (candidates == null ? List.<SupplierCandidate>of() : candidates).forEach(c -> byId.put(c.supplierId(), c));
        List<SupplierEvidence> evidence = new ArrayList<>();
        List<CandidateResult> all = new ArrayList<>();
        for (SupplierOffer offer : offers == null ? List.<SupplierOffer>of() : offers) {
            SupplierCandidate candidate = byId.getOrDefault(offer.supplierId(),
                    new SupplierCandidate(offer.supplierId(), offer.supplierId(), offer.source()));
            List<String> failures = failures(state, candidate, offer);
            String fact = "商品 " + offer.productName() + " 单价 " + offer.unitPrice() + " " + offer.currency()
                    + "，总价 " + offer.totalPrice() + "，交期 " + offer.leadTimeDays() + " 天，规格 " + offer.specifications();
            String evidenceRef = EvidenceIdFactory.id(candidate.supplierId(), "OFFER", offer.source(),
                    offer.sourceRecordId(), offer.sourceSnapshot(), offer.sourceAsOf().toString(), fact);
            evidence.add(new SupplierEvidence(evidenceRef, candidate.supplierId(), "OFFER", offer.source(), fact, offer.fetchedAt(),
                    offer.sourceRecordId(), offer.sourceSnapshot(), offer.sourceAsOf(), offer.sourceDigest()));
            all.add(new CandidateResult(candidate, offer, failures.isEmpty(), failures, List.of(evidenceRef)));
        }
        // 不排序、不评分、不生成 recommendedSupplier。即使只有一个 Eligible，也必须由 Agent
        // 明确提交选择，再交给 recommendation_finalize 做当前快照验证。
        return new Evaluation(all, evidence);
    }

    private List<String> failures(ProcurementCaseState state, SupplierCandidate candidate, SupplierOffer offer) {
        List<String> result = new ArrayList<>();
        state.hardConstraints().keySet().stream().filter(key -> !SUPPORTED_HARD_CONSTRAINTS.contains(key))
                .sorted().forEach(key -> result.add("UNSUPPORTED_HARD_CONSTRAINT:" + key));
        if (!offer.currency().equalsIgnoreCase(state.currency())) result.add("CURRENCY_MISMATCH");
        if (state.excludedSuppliers().stream().anyMatch(excluded -> equalsSupplier(excluded, candidate))) result.add("EXCLUDED_SUPPLIER");
        if (state.budget() != null && offer.totalPrice().compareTo(state.budget()) > 0) result.add("BUDGET_EXCEEDED");
        if (state.requiredDeliveryDays() != null && offer.leadTimeDays() > state.requiredDeliveryDays()) result.add("DELIVERY_HARD_CONSTRAINT_FAILED");
        String gpu = state.hardConstraints().get("gpuMemoryMinGb");
        if (gpu != null) {
            try {
                if (number(offer.specifications().get("gpuMemoryGb")) < Integer.parseInt(gpu)) {
                    result.add("HARD_CONSTRAINT_FAILED:gpuMemoryMinGb");
                }
            } catch (NumberFormatException exception) {
                result.add("INVALID_HARD_CONSTRAINT:gpuMemoryMinGb");
            }
        }
        return List.copyOf(result);
    }

    public List<String> matchedConstraints(ProcurementCaseState state, SupplierOffer offer) {
        List<String> result = new ArrayList<>();
        if (state.budget() == null || offer.totalPrice().compareTo(state.budget()) <= 0) result.add("BUDGET");
        if (state.requiredDeliveryDays() == null || offer.leadTimeDays() <= state.requiredDeliveryDays()) result.add("DELIVERY");
        if (state.hardConstraints().containsKey("gpuMemoryMinGb")) result.add("gpuMemoryMinGb");
        return List.copyOf(result);
    }

    private boolean equalsSupplier(String excluded, SupplierCandidate candidate) {
        return excluded.equalsIgnoreCase(candidate.supplierId()) || excluded.equalsIgnoreCase(candidate.supplierName());
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value == null ? "0" : value)); }

    public record CandidateResult(SupplierCandidate candidate, SupplierOffer offer, boolean eligible,
                                  List<String> failures, List<String> evidenceRefs) {
        public CandidateResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record Evaluation(List<CandidateResult> candidates, List<SupplierEvidence> evidence) {
        public Evaluation {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
