package com.agent.platform.procurement.tool;

import com.agent.platform.procurement.application.ProcurementDecisionEngine;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;
import com.agent.platform.procurement.provider.ProcurementDataProvider;
import com.agent.platform.tool.ContextualToolHandler;
import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProcurementToolHandler implements ContextualToolHandler {
    private final ProcurementDataProvider provider;
    private final ProcurementDecisionEngine engine;
    private final ObjectMapper objectMapper;

    public ProcurementToolHandler(ProcurementDataProvider provider, ObjectMapper objectMapper) {
        this.provider = provider; this.objectMapper = objectMapper; this.engine = new ProcurementDecisionEngine();
    }

    @Override public boolean supports(String toolName) { return ProcurementToolCatalog.SUPPLIER_SEARCH.equals(toolName) || ProcurementToolCatalog.SUPPLIER_EVIDENCE.equals(toolName); }

    @Override
    public ToolCallResult execute(ToolCallRequest request, ToolExecutionContext context) {
        try {
            if (request == null || !supports(request.toolName())) return failure(request, "unsupported procurement tool");
            ProcurementCaseState state = state(request.arguments(), context);
            strictValidate(request.toolName(), request.arguments(), state);
            if (ProcurementToolCatalog.SUPPLIER_SEARCH.equals(request.toolName())) return search(request, state);
            String supplierId = string(request.arguments(), "supplierId");
            List<SupplierEvidence> evidence = provider.getSupplierEvidence(supplierId, state);
            ToolCallResult result = success(request, Map.of("supplierId", supplierId, "evidence", evidence, "evidenceRefs", evidence.stream().map(SupplierEvidence::evidenceId).toList()));
            return withMetadata(result, Map.of("enoughEvidence", !evidence.isEmpty(), "evidenceCount", evidence.size()));
        }
        catch (IllegalArgumentException exception) { return failure(request, exception.getMessage()); }
        catch (RuntimeException exception) { return failure(request, "procurement provider failed: " + exception.getClass().getSimpleName()); }
    }

    private ToolCallResult search(ToolCallRequest request, ProcurementCaseState state) {
        List<SupplierCandidate> candidates = provider.searchSuppliers(state);
        List<SupplierOffer> offers = provider.getSupplierOffers(state, candidates);
        ProcurementDecisionEngine.Evaluation evaluation = engine.evaluate(state, candidates, offers);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", candidates); result.put("offers", offers);
        result.put("evaluations", evaluation.candidates()); result.put("evidence", evaluation.evidence());
        result.put("recommendation", evaluation.recommendation());
        com.agent.platform.procurement.application.ProcurementRecommendationVerifier.verify(
                evaluation.recommendation(), evaluation.evidence());
        result.put("source", "provider-canonical-model");
        return success(request, result);
    }

    private ProcurementCaseState state(Map<String, Object> args, ToolExecutionContext context) {
        Object existing = context == null ? null : context.attributes().get("procurementCaseState");
        ProcurementCaseState base = existing instanceof ProcurementCaseState state ? state
                : existing instanceof Map<?, ?> map ? objectMapper.convertValue(map, ProcurementCaseState.class) : ProcurementCaseState.empty();
        boolean trustedState = existing != null;
        String description = trustedState ? base.productDescription() : string(args, "productDescription");
        String category = trustedState ? base.productCategory() : string(args, "productCategory");
        Integer quantity = trustedState ? base.quantity() : integer(args, "quantity");
        BigDecimal budget = trustedState ? base.budget() : decimal(args, "budget");
        Integer delivery = trustedState ? base.requiredDeliveryDays() : integer(args, "requiredDeliveryDays");
        Map<String, String> hard = trustedState
                ? base.hardConstraints() : stringMap(args.get("hardConstraints"), base.hardConstraints());
        Map<String, String> preferences = trustedState
                ? base.preferences() : stringMap(args.get("preferences"), base.preferences());
        Set<String> excluded = trustedState
                ? base.excludedSuppliers() : stringSet(args.get("excludedSuppliers"), base.excludedSuppliers());
        String currency = trustedState ? base.currency() : blankOr(string(args, "currency"), base.currency());
        List<String> missing = trustedState ? base.missingFields() : missing(description, quantity, budget);
        return new ProcurementCaseState(preferTrusted(base.productCategory(), category), preferTrusted(base.productDescription(), description),
                base.quantity() != null ? base.quantity() : quantity, base.budget() != null ? base.budget() : budget,
                currency, base.requiredDeliveryDays() != null ? base.requiredDeliveryDays() : delivery,
                hard, preferences, excluded, missing, "SOURCING");
    }

    private void strictValidate(String tool, Map<String, Object> args, ProcurementCaseState state) {
        if (args == null) throw new IllegalArgumentException("arguments are required");
        Set<String> allowed = ProcurementToolCatalog.SUPPLIER_SEARCH.equals(tool)
                ? Set.of("productDescription", "productCategory", "quantity", "budget", "currency", "requiredDeliveryDays", "hardConstraints", "preferences", "excludedSuppliers")
                : Set.of("supplierId", "productDescription", "productCategory", "quantity", "hardConstraints");
        if (args.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw new IllegalArgumentException("unknown argument");
        if (ProcurementToolCatalog.SUPPLIER_SEARCH.equals(tool) && !state.missingFields().isEmpty()) {
            throw new IllegalArgumentException("procurement CaseState is incomplete; clarification is required");
        }
        if (state.productDescription().isBlank()) throw new IllegalArgumentException("productDescription is required");
        if (state.quantity() != null && state.quantity() <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (state.budget() != null && state.budget().signum() < 0) throw new IllegalArgumentException("budget must not be negative");
        if (state.requiredDeliveryDays() != null && (state.requiredDeliveryDays() < 1 || state.requiredDeliveryDays() > 3650)) throw new IllegalArgumentException("requiredDeliveryDays is out of range");
        if (state.hardConstraints().containsKey("gpuMemoryMinGb")) {
            try { if (Integer.parseInt(state.hardConstraints().get("gpuMemoryMinGb")) < 1) throw new NumberFormatException(); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("gpuMemoryMinGb must be a positive integer"); }
        }
        state.hardConstraints().keySet().stream()
                .filter(key -> !ProcurementDecisionEngine.SUPPORTED_HARD_CONSTRAINTS.contains(key))
                .findFirst().ifPresent(key -> { throw new IllegalArgumentException("unsupported hard constraint: " + key); });
        if (ProcurementToolCatalog.SUPPLIER_EVIDENCE.equals(tool) && string(args, "supplierId").isBlank()) throw new IllegalArgumentException("supplierId is required");
    }

    private ToolCallResult success(ToolCallRequest request, Object value) {
        return new ToolCallResult(request.toolName(), true, write(value), "", Map.of("provider", "procurement", "readOnly", true, "recordCount", value instanceof Map<?, ?> map ? map.size() : 1));
    }
    private ToolCallResult withMetadata(ToolCallResult result, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata()); metadata.putAll(extra);
        return new ToolCallResult(result.toolName(), result.success(), result.content(), result.errorMessage(), metadata);
    }
    private ToolCallResult failure(ToolCallRequest request, String message) { return new ToolCallResult(request == null ? "" : request.toolName(), false, "", message == null ? "invalid procurement request" : message, Map.of("provider", "procurement", "readOnly", true)); }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("failed to serialize procurement result", e); } }
    private String string(Map<String, Object> args, String key) { Object value = args == null ? null : args.get(key); return value == null ? "" : String.valueOf(value).trim(); }
    private Integer integer(Map<String, Object> args, String key) { Object value = args == null ? null : args.get(key); if (value == null) return null; if (value instanceof Number n) return n.intValue(); try { return Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer"); } }
    private BigDecimal decimal(Map<String, Object> args, String key) { Object value = args == null ? null : args.get(key); if (value == null) return null; try { return new BigDecimal(String.valueOf(value)); } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be a number"); } }
    private String blankOr(String value, String fallback) { return value.isBlank() ? fallback : value; }
    private String preferTrusted(String trusted, String proposed) { return trusted == null || trusted.isBlank() ? proposed : trusted; }
    private List<String> missing(String description, Integer quantity, BigDecimal budget) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (description == null || description.isBlank()) result.add("productDescription");
        if (quantity == null) result.add("quantity");
        if (budget == null) result.add("budget");
        return List.copyOf(result);
    }
    private Map<String, String> stringMap(Object value, Map<String, String> fallback) { if (!(value instanceof Map<?, ?> map)) return fallback; Map<String, String> result = new LinkedHashMap<>(); map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v))); return Map.copyOf(result); }
    private Set<String> stringSet(Object value, Set<String> fallback) { if (!(value instanceof List<?> list)) return fallback; return list.stream().map(String::valueOf).map(String::trim).filter(v -> !v.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
}
