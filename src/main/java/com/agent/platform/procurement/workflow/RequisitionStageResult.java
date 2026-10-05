package com.agent.platform.procurement.workflow;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** Model proposal only. Optional Patch fields preserve the existing partial-update protocol. */
public record RequisitionStageResult(Intent intent, @ArraySchema(maxItems=64) List<String> evidenceRefs, CaseChanges caseChanges,
        String subjectRequisitionId, String departmentId, @ArraySchema(maxItems=64) List<BlockerCandidate> blockerCandidates,
        @ArraySchema(maxItems=64) List<String> clarifications, @ArraySchema(maxItems=64) List<DeferredCheck> deferredChecks) {
    public enum Intent { ANALYZE, RFQ, CHAT }
    public enum Blocker { ITEM_INACTIVE, REQUISITION_CLOSED, BUDGET_UNAVAILABLE, BUDGET_INSUFFICIENT, DUPLICATE_REQUEST }
    public enum DeferredCheck { DELIVERY_FEASIBILITY, SUPPLIER_PERFORMANCE, SUPPLIER_PRICING }
    public record BlockerCandidate(Blocker type,@ArraySchema(maxItems=64) List<String> evidenceRefs) {}
    public enum ConstraintKey { gpuMemoryMinGb }
    public enum PreferenceKey { deliveryPriority, pricePriority }
    public enum Priority { HIGH }
    public enum ClearableField { productCategory, productDescription, quantity, budget, requiredDeliveryDays }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record HardConstraints(@JsonProperty(required=false) @Schema(pattern="^[1-9][0-9]*$") String gpuMemoryMinGb) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Preferences(@JsonProperty(required=false) Priority deliveryPriority,@JsonProperty(required=false) Priority pricePriority) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record CaseChanges(
        @JsonProperty(required=false) @Schema(maxLength=100) String productCategory,
        @JsonProperty(required=false) @Schema(minLength=1,maxLength=500) String productDescription,
        @JsonProperty(required=false) @Schema(minimum="1",maximum="100000") Integer quantity,
        @JsonProperty(required=false) @Schema(minimum="0") BigDecimal budget,
        @JsonProperty(required=false) @Schema(pattern="^[A-Za-z]{3}$") String currency,
        @JsonProperty(required=false) @Schema(minimum="1",maximum="3650") Integer requiredDeliveryDays,
        @JsonProperty(required=false) HardConstraints hardConstraintsUpsert,
        @JsonProperty(required=false) Set<ConstraintKey> hardConstraintsRemove,
        @JsonProperty(required=false) Preferences preferencesUpsert,
        @JsonProperty(required=false) Set<PreferenceKey> preferencesRemove,
        @JsonProperty(required=false) Set<String> excludedSuppliersAdd,
        @JsonProperty(required=false) Set<String> excludedSuppliersRemove,
        @JsonProperty(required=false) Set<ClearableField> fieldsToClear) {}
}
