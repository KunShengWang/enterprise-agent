package com.agent.platform.procurement.workflow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Server-owned identity: the successful Finalize receipt plus its committed Case version. */
public record RecommendationEvidenceManifest(String recommendationId, long recommendationVersion,
        String caseId, long caseVersion, String selectedSupplierId, List<String> validatedEvidenceRefs,
        String createdAt, String authorizationSource) {
    public RecommendationEvidenceManifest { validatedEvidenceRefs=List.copyOf(validatedEvidenceRefs); }

    public static RecommendationEvidenceManifest create(String ref, JsonNode canonical, Instant now) {
        var recommendation=canonical.path("recommendation");
        String supplier=recommendation.path("recommendedSupplier").path("supplierId").asText();
        if(supplier.isBlank() || !supplier.equals(recommendation.path("selectedOffer").path("supplierId").asText()))
            throw new IllegalArgumentException("manifest supplier mismatch");
        var existing=new HashSet<String>();
        canonical.path("evidence").forEach(e->existing.add(e.path("evidenceId").asText()));
        var refs=new LinkedHashSet<String>();
        for(var evidence:recommendation.path("evidenceRefs")) {
            if(!evidence.isTextual() || !existing.contains(evidence.asText()))
                throw new IllegalArgumentException("manifest evidence absent from verified Finalize snapshot");
            refs.add(evidence.asText());
        }
        if(refs.isEmpty())throw new IllegalArgumentException("manifest requires validated recommendation evidence");
        return new RecommendationEvidenceManifest(ref,canonical.path("caseVersion").asLong(),canonical.path("caseId").asText(),
                canonical.path("caseVersion").asLong(),supplier,List.copyOf(refs),now.toString(),"verified-provider-snapshot");
    }

    public static RecommendationEvidenceManifest readAndVerify(String ref, JsonNode canonical, ObjectMapper json) {
        var stored=json.treeToValue(canonical.path("recommendationEvidenceManifest"),RecommendationEvidenceManifest.class);
        if(!stored.equals(create(ref,canonical,Instant.parse(stored.createdAt()))))
            throw new IllegalArgumentException("recommendation manifest integrity mismatch");
        return stored;
    }
}
