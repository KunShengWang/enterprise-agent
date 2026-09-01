package com.agent.platform.procurement.tool;

import com.agent.platform.procurement.application.ProcurementCasePatchMerger;
import com.agent.platform.procurement.application.ProcurementCaseService;
import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.application.ProcurementRecommendationFinalizer;
import com.agent.platform.procurement.model.ProcurementCase;
import com.agent.platform.procurement.model.ProcurementCasePatch;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.ProcurementRecommendationDraft;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import com.agent.platform.procurement.persistence.ProcurementCaseStore;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.tool.ContextualToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProcurementToolHandler implements ContextualToolHandler {
    private static final Set<String> PATCH_ARGUMENTS = Set.of(
            "productCategory", "productDescription", "quantity", "budget", "currency", "requiredDeliveryDays",
            "hardConstraintsUpsert", "hardConstraintsRemove", "preferencesUpsert", "preferencesRemove",
            "excludedSuppliersAdd", "excludedSuppliersRemove", "fieldsToClear");

    private final ProcurementDataProvider provider;
    private final ProcurementCaseStore caseStore;
    private final ProcurementCaseService caseService;
    private final ProcurementRecommendationFinalizer finalizer;
    private final ProcurementCasePatchMerger patchMerger;
    private final ObjectMapper objectMapper;
    private final ProcurementDecisionEngine decisionEngine;

    @Autowired
    public ProcurementToolHandler(ProcurementDataProvider provider,
                                  ObjectMapper objectMapper,
                                  ProcurementCaseStore caseStore,
                                  ProcurementCaseService caseService,
                                  ProcurementRecommendationFinalizer finalizer,
                                  ProcurementCasePatchMerger patchMerger,
                                  ProcurementDecisionEngine decisionEngine) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.caseStore = caseStore;
        this.caseService = caseService;
        this.finalizer = finalizer;
        this.patchMerger = patchMerger;
        this.decisionEngine = decisionEngine;
    }

    @Override
    public boolean supports(String toolName) {
        return ProcurementToolCatalog.CASE_PATCH.equals(toolName)
                || ProcurementToolCatalog.SUPPLIER_SEARCH.equals(toolName)
                || ProcurementToolCatalog.SUPPLIER_EVIDENCE.equals(toolName)
                || ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(toolName);
    }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        try {
            if (request == null || !supports(request.toolName())) return failure(request, "unsupported procurement tool");
            Map<String, Object> arguments = request.arguments();
            strictValidate(request.toolName(), arguments);
            return switch (request.toolName()) {
                case ProcurementToolCatalog.CASE_PATCH -> patch(request, context, arguments);
                case ProcurementToolCatalog.SUPPLIER_SEARCH -> search(request, context, arguments);
                case ProcurementToolCatalog.SUPPLIER_EVIDENCE -> evidence(request, context, arguments);
                case ProcurementToolCatalog.RECOMMENDATION_FINALIZE -> finalizeRecommendation(request, context, arguments);
                default -> failure(request, "unsupported procurement tool");
            };
        }
        catch (com.agent.platform.procurement.application.ProcurementCaseVersionConflictException exception) {
            return failure(request, exception.getMessage());
        }
        catch (IllegalArgumentException exception) {
            return failure(request, exception.getMessage());
        }
        catch (RuntimeException exception) {
            return failure(request, "procurement provider failed: " + exception.getClass().getSimpleName());
        }
    }

    private ToolCallResult patch(ToolCallRequest request, ToolExecutionContext context, Map<String, Object> arguments) {
        ToolExecutionContext trusted = requireIdentity(context);
        ProcurementCasePatch patch = objectMapper.convertValue(arguments, ProcurementCasePatch.class);
        patchMerger.validate(patch);
        ProcurementCase value = caseService.applyPatch(trusted.tenantId(), trusted.sessionId(), trusted.userId(),
                patch, request.requestId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", value.caseId());
        result.put("caseVersion", value.version());
        result.put("status", value.status());
        result.put("state", value.state());
        result.put("missingFields", value.state().missingFields());
        result.put("currentPhase", value.state().currentPhase());
        result.put("source", "authoritative-procurement-case-store");
        return success(request, result);
    }

    private ToolCallResult search(ToolCallRequest request, ToolExecutionContext context, Map<String, Object> arguments) {
        ProcurementCase current = currentCase(context);
        ProcurementCaseState state = current.state();
        if (!state.missingFields().isEmpty()) {
            throw new IllegalArgumentException("procurement CaseState is incomplete; clarification is required");
        }
        List<SupplierCandidate> candidates = provider.searchSuppliers(state);
        List<SupplierOffer> offers = provider.getSupplierOffers(state, candidates);
        ProcurementDecisionEngine.Evaluation evaluation = decisionEngine.evaluate(state, candidates, offers);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseVersion", current == null ? null : current.version());
        result.put("candidates", candidates);
        result.put("offers", offers);
        result.put("evaluations", evaluation.candidates());
        result.put("eligibleSuppliers", evaluation.candidates().stream().filter(
                ProcurementDecisionEngine.CandidateResult::eligible)
                .map(ProcurementDecisionEngine.CandidateResult::candidate).toList());
        Map<String, SupplierEvidence> evidenceById = new LinkedHashMap<>();
        candidates.forEach(candidate -> provider.getSupplierEvidence(candidate.supplierId(), state)
                .forEach(evidence -> evidenceById.putIfAbsent(evidence.evidenceId(), evidence)));
        result.put("evidence", List.copyOf(evidenceById.values()));
        result.put("recommendationAvailable", false);
        result.put("source", "provider-canonical-model");
        return success(request, result);
    }

    private ToolCallResult evidence(ToolCallRequest request, ToolExecutionContext context, Map<String, Object> arguments) {
        ProcurementCase current = currentCase(context);
        ProcurementCaseState state = current.state();
        if (!state.missingFields().isEmpty()) {
            throw new IllegalArgumentException("procurement CaseState is incomplete; clarification is required");
        }
        String supplierId = string(arguments, "supplierId");
        List<SupplierEvidence> evidence = provider.getSupplierEvidence(supplierId, state);
        ToolCallResult result = success(request, Map.of("supplierId", supplierId, "evidence", evidence,
                "evidenceRefs", evidence.stream().map(SupplierEvidence::evidenceId).toList(),
                "caseVersion", current == null ? 0 : current.version()));
        return withMetadata(result, Map.of("enoughEvidence", !evidence.isEmpty(), "evidenceCount", evidence.size()));
    }

    private ToolCallResult finalizeRecommendation(ToolCallRequest request,
                                                   ToolExecutionContext context,
                                                   Map<String, Object> arguments) {
        ToolExecutionContext trusted = requireIdentity(context);
        ProcurementRecommendationDraft draft = objectMapper.convertValue(arguments, ProcurementRecommendationDraft.class);
        ProcurementRecommendationFinalizer.Finalization result = finalizer.finalize(
                trusted.tenantId(), trusted.userId(), trusted.sessionId(), draft);
        return success(request, Map.of(
                "caseId", result.procurementCase().caseId(),
                "caseVersion", result.procurementCase().version(),
                "recommendation", result.recommendation(),
                "evidence", result.evidence(),
                "source", "verified-provider-snapshot"));
    }

    private ProcurementCase currentCase(ToolExecutionContext context) {
        ToolExecutionContext trusted = requireIdentity(context);
        return caseStore.findByTenantUserAndConversationId(trusted.tenantId(), trusted.userId(), trusted.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("procurement Case not found"));
    }

    private ToolExecutionContext requireIdentity(ToolExecutionContext context) {
        if (context == null || context.tenantId().isBlank() || context.userId().isBlank() || context.sessionId().isBlank()) {
            throw new IllegalArgumentException("trusted tenantId, userId and conversationId are required");
        }
        return context;
    }

    private void strictValidate(String tool, Map<String, Object> args) {
        if (args == null) throw new IllegalArgumentException("arguments are required");
        Set<String> allowed = switch (tool) {
            case ProcurementToolCatalog.CASE_PATCH -> PATCH_ARGUMENTS;
            case ProcurementToolCatalog.SUPPLIER_SEARCH -> Set.of();
            case ProcurementToolCatalog.SUPPLIER_EVIDENCE -> Set.of("supplierId");
            case ProcurementToolCatalog.RECOMMENDATION_FINALIZE -> Set.of("evaluatedCaseVersion", "selectedSupplierId", "evidenceRefs", "reasons", "tradeoffs", "risks", "uncertainties", "confidence");
            default -> Set.of();
        };
        if (args.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw new IllegalArgumentException("unknown argument");
        if (ProcurementToolCatalog.CASE_PATCH.equals(tool)) {
            patchMerger.validate(objectMapper.convertValue(args, ProcurementCasePatch.class));
        }
        if (ProcurementToolCatalog.RECOMMENDATION_FINALIZE.equals(tool)) validateFinalizerArguments(args);
        if (ProcurementToolCatalog.SUPPLIER_EVIDENCE.equals(tool) && string(args, "supplierId").isBlank()) {
            throw new IllegalArgumentException("supplierId is required");
        }
    }

    private void validateFinalizerArguments(Map<String, Object> args) {
        for (String required : List.of("evaluatedCaseVersion", "selectedSupplierId", "evidenceRefs", "reasons", "tradeoffs", "risks", "uncertainties", "confidence")) {
            if (!args.containsKey(required) || args.get(required) == null) throw new IllegalArgumentException("missing required argument: " + required);
        }
        if (integer(args, "evaluatedCaseVersion") < 0) throw new IllegalArgumentException("evaluatedCaseVersion must not be negative");
        if (string(args, "selectedSupplierId").isBlank()) throw new IllegalArgumentException("selectedSupplierId is required");
        if (stringList(args.get("evidenceRefs")).isEmpty()) throw new IllegalArgumentException("evidenceRefs must not be empty");
        if (stringList(args.get("reasons")).isEmpty()) throw new IllegalArgumentException("reasons must not be empty");
        double confidence = number(args.get("confidence"));
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be between 0 and 1");
        for (String field : List.of("reasons", "tradeoffs", "risks", "uncertainties")) stringList(args.get(field));
    }

    private ToolCallResult success(ToolCallRequest request, Object value) {
        return new ToolCallResult(request.toolName(), true, write(value), "",
                metadata(request.toolName()));
    }

    private ToolCallResult withMetadata(ToolCallResult result, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.putAll(extra);
        return new ToolCallResult(result.toolName(), result.success(), result.content(), result.errorMessage(), metadata);
    }

    private ToolCallResult failure(ToolCallRequest request, String message) {
        return new ToolCallResult(request == null ? "" : request.toolName(), false, "",
                message == null ? "invalid procurement request" : message,
                metadata(request == null ? "" : request.toolName()));
    }

    private Map<String, Object> metadata(String toolName) {
        boolean mutatesCase = ProcurementToolCatalog.CASE_PATCH.equals(toolName);
        return Map.of("provider", "procurement", "readOnly", !mutatesCase, "sideEffect", mutatesCase);
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("failed to serialize procurement result", exception); }
    }

    private String string(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integer(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException(key + " must be an integer"); }
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("number argument is invalid"); }
    }
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("argument must be an array");
        return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

}
