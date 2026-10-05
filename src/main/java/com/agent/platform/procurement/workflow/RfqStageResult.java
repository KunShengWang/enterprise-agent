package com.agent.platform.procurement.workflow;
import java.util.List;
public record RfqStageResult(boolean requestRfq,String explanation,String recommendationRef,@io.swagger.v3.oas.annotations.media.ArraySchema(maxItems=64) List<String> evidenceRefs) {}
