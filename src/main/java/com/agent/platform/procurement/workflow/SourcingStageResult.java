package com.agent.platform.procurement.workflow;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.agent.platform.procurement.model.ProcurementTradeoffDimension;
import com.agent.platform.runtime.StructuredOutputSpec;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

public record SourcingStageResult(Status status, RecommendationProposal recommendation,
        @ArraySchema(maxItems=64) List<String> evidenceRefs,@ArraySchema(maxItems=64) List<String> tradeoffs,@ArraySchema(maxItems=64) List<String> riskFindings)
        implements StructuredOutputSpec.Validatable {
    public enum Status { RECOMMENDATION, NO_ELIGIBLE_SUPPLIER }
    /** Empty object is legal only for NO_ELIGIBLE_SUPPLIER, as in the previous protocol. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RecommendationProposal(
        @JsonProperty(required=false) @Schema(minimum="0") Long evaluatedCaseVersion,
        @JsonProperty(required=false) @Schema(minLength=1) String selectedSupplierId,
        @JsonProperty(required=false) @ArraySchema(minItems=1,schema=@Schema(minLength=1)) List<String> evidenceRefs,
        @JsonProperty(required=false) List<ProcurementTradeoffDimension> tradeoffDimensions,
        @JsonProperty(required=false) @Schema(minimum="0",maximum="1") Double confidence) {}
    @Override public void validateStructure() {
        if(status==Status.RECOMMENDATION && (recommendation.evaluatedCaseVersion()==null
                || recommendation.selectedSupplierId()==null || recommendation.evidenceRefs()==null
                || recommendation.tradeoffDimensions()==null || recommendation.confidence()==null))
            throw new IllegalArgumentException("RECOMMENDATION requires complete recommendation fields");
    }
}
