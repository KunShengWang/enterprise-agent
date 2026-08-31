package com.agent.platform.procurement.application;

import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.EvidenceIdFactory;
import com.agent.platform.procurement.model.RejectedCandidate;
import com.agent.platform.procurement.model.SourcingRecommendation;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Java 负责总价、预算、排除供应商和规格/交期硬约束；Agent 只负责权衡和解释。 */
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
        all.sort(Comparator.comparing(result -> result.offer().totalPrice()));
        List<CandidateResult> eligible = all.stream().filter(CandidateResult::eligible).toList();
        // Java 只确定硬约束资格；多个 Eligible 候选的开放式权衡交给 Agent，避免隐藏价格/交期权重。
        CandidateResult recommended = eligible.size() == 1 ? eligible.get(0) : null;
        List<SupplierCandidate> alternatives = eligible.stream().filter(result -> result != recommended).map(CandidateResult::candidate).toList();
        List<RejectedCandidate> rejected = all.stream().filter(result -> !result.eligible())
                .map(result -> new RejectedCandidate(result.candidate(), result.failures(), result.evidenceRefs())).toList();
        List<String> matched = recommended == null ? List.of() : matched(state, recommended.offer());
        List<String> tradeoffs = recommended == null
                ? (eligible.isEmpty() ? List.of("没有同时满足当前硬约束的供应商") : List.of("多个候选满足硬约束，需要 Agent 根据偏好进一步权衡"))
                : tradeoffs(state, recommended.offer(), all);
        List<String> reasons = recommended == null ? List.of(eligible.isEmpty() ? "没有 Eligible Supplier" : "存在多个 Eligible Supplier，未由 Java 隐式代选") : List.of(
                recommended.candidate().supplierName() + "满足当前硬约束，总价 " + recommended.offer().totalPrice()
                        + "，交期 " + recommended.offer().leadTimeDays() + " 天");
        List<String> risks = recommended == null ? List.of("需要放宽约束或人工补充供应商证据") : List.of("报价和交期来自当前 synthetic/provider 快照，不代表实时承诺");
        double confidence = recommended == null ? 0.0 : 1.0;
        List<String> supportingEvidence = recommended == null ? List.of() : recommended.evidenceRefs();
        return new Evaluation(all, evidence, new SourcingRecommendation(
                recommended == null ? null : recommended.candidate(), alternatives, matched, rejected,
                tradeoffs, reasons, risks, supportingEvidence,
                recommended == null ? List.of(eligible.isEmpty() ? "没有可推荐供应商" : "需要 Agent 结合用户偏好选择候选") : List.of("供应商库存、质量历史和实时有效期未在当前数据集中提供"), confidence));
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
        if (gpu != null && number(offer.specifications().get("gpuMemoryGb")) < Integer.parseInt(gpu)) result.add("HARD_CONSTRAINT_FAILED:gpuMemoryMinGb");
        return List.copyOf(result);
    }

    private List<String> matched(ProcurementCaseState state, SupplierOffer offer) {
        List<String> result = new ArrayList<>();
        if (state.budget() == null || offer.totalPrice().compareTo(state.budget()) <= 0) result.add("BUDGET");
        if (state.requiredDeliveryDays() == null || offer.leadTimeDays() <= state.requiredDeliveryDays()) result.add("DELIVERY");
        if (state.hardConstraints().containsKey("gpuMemoryMinGb")) result.add("gpuMemoryMinGb");
        return List.copyOf(result);
    }

    private List<String> tradeoffs(ProcurementCaseState state, SupplierOffer selected, List<CandidateResult> all) {
        List<String> result = new ArrayList<>();
        all.stream().min(Comparator.comparing(resultValue -> resultValue.offer().totalPrice())).ifPresent(lowest -> {
            if (lowest.offer().totalPrice().compareTo(selected.totalPrice()) < 0) result.add("推荐方案不是最低价，额外成本 " + selected.totalPrice().subtract(lowest.offer().totalPrice()));
        });
        if (state.preferences().containsKey("deliveryPriority")) result.add("当前偏好交期优先");
        return List.copyOf(result);
    }

    private boolean equalsSupplier(String excluded, SupplierCandidate candidate) {
        return excluded.equalsIgnoreCase(candidate.supplierId()) || excluded.equalsIgnoreCase(candidate.supplierName());
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value == null ? "0" : value)); }

    public record CandidateResult(SupplierCandidate candidate, SupplierOffer offer, boolean eligible,
                                  List<String> failures, List<String> evidenceRefs) { }
    public record Evaluation(List<CandidateResult> candidates, List<SupplierEvidence> evidence,
                             SourcingRecommendation recommendation) { }
}
